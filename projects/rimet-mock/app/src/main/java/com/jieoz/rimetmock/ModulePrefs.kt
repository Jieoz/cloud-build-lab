package com.jieoz.rimetmock

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * The single config channel between the module app and the host (DingTalk).
 *
 * libxposed exposes TWO different preference interfaces and they are NOT interchangeable
 * (the two-interface rule the sibling modules already follow):
 *  - the module APP must write through [XposedService], bound via [XposedServiceHelper].
 *    The hook-side [XposedInterface.getRemotePreferences] view inside the app process is a
 *    read-only mirror — edits made through it never publish, which was exactly the bug that
 *    made every saved profile invisible to DingTalk in the 0.1 build;
 *  - the HOST process reads through the hook-side [XposedInterface.getRemotePreferences].
 *
 * Binder delivery is PUSH-only: LSPosed's daemon (LSPModuleService.sendBinder) calls this
 * app's XposedProvider when this app's uid turns active/idle/uncached. A cold start can miss
 * that window (the push can land before the provider is published, and the daemon does not
 * retry until the NEXT uid transition), so [publishToRemote] waits a bounded 2s for the
 * binder instead of failing instantly, and [onServiceBind] auto-republishes whatever is in
 * local prefs the moment the binder arrives — a save made while unbound heals itself on the
 * next app open with zero user action.
 *
 * Payload: the whole [MockState] as one JSON key, plus a separate log-switch boolean. There is
 * no world-readable XML (the file LSPosed 2.2 warns about, 2.3 removes) and no ContentProvider
 * fallback. A failed publish is returned to the caller and shown in the UI, never swallowed.
 */
object ModulePrefs {

    @Volatile
    private var framework: XposedInterface? = null

    @Volatile
    private var service: XposedService? = null

    /** App context captured on first UI touch; enables the bind-time auto-sync. */
    @Volatile
    private var appContext: Context? = null

    /** UI hook: fired (on a binder thread) whenever the service binds. */
    @Volatile
    var onBound: (() -> Unit)? = null

    val isBound: Boolean get() = service != null

    @Volatile
    var lastPublishError: String? = null
        private set

    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                onBound?.invoke()
                autoSync()
            }

            override fun onServiceDied(dead: XposedService) {
                if (service === dead) service = null
            }
        })
    }

    /** Called from the app UI once so [autoSync] knows what to publish. Idempotent. */
    fun prime(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun bind(base: XposedInterface) {
        framework = base
    }

    /** Push whatever the local prefs hold to the remote store. Silent best-effort. */
    private fun autoSync() {
        val context = appContext ?: return
        Thread {
            try {
                publishToRemote(localPrefs(context))
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun localPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    /** Host-side read: the current state, or an empty default before anything is saved. */
    fun state(): MockState = MockState.fromJson(framework?.let { readRemoteString(it, Constants.K_STATE) })

    /** Host-side read of the diagnostic log switch. Defaults closed (fail closed). */
    fun logEnabled(): Boolean =
        framework?.let { readRemoteBoolean(it, Constants.K_LOG) } ?: false

    /**
     * Host-side provider fallback. On some devices / LSPosed builds the service binder is
     * never pushed to the module app, so the app cannot PUBLISH to the remote store — but the
     * HOST can always reach the module app's own ConfigProvider (same channel the original
     * module used). When the app is unbound the local file is the freshest copy anyway: the
     * UI writes there first, and the provider serves it straight from disk.
     */
    private fun readRemoteString(base: XposedInterface, key: String): String? {
        val remote = try {
            base.getRemotePreferences(Constants.PREFS).getString(key, null)
        } catch (_: Throwable) {
            null
        }
        if (remote != null) return remote
        return parseProviderState(providerQuery(ConfigProvider.URI_STATE, Constants.K_STATE))
    }

    private fun readRemoteBoolean(base: XposedInterface, key: String): Boolean {
        val remote = try {
            base.getRemotePreferences(Constants.PREFS).getBoolean(key, false)
        } catch (_: Throwable) {
            false
        }
        if (remote) return true
        return providerQuery(ConfigProvider.URI_LOG, Constants.K_LOG) == "1"
    }

    private fun providerQuery(uri: Uri, column: String): String? = try {
        HostContext.app!!.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(column)) else null
        }
    } catch (_: Throwable) {
        null
    }

    /** Parse the provider's state JSON and re-serialize through the same model, so a null/empty result collapses to null. */
    private fun parseProviderState(row: String?): String? {
        if (row.isNullOrEmpty()) return null
        val s = MockState.fromJson(row)
        return if (s.profiles.isEmpty() && !s.enabled) null else s.toJson()
    }

    /** App-side convenience: last locally-written state (authoritative for the UI). */
    fun load(context: Context): MockState =
        MockState.fromJson(localPrefs(context).getString(Constants.K_STATE, null))

    fun logEnabledLocal(context: Context): Boolean =
        localPrefs(context).getBoolean(Constants.K_LOG, false)

    /**
     * App-side save: the LOCAL write is the authoritative truth — the host reads it live via
     * [ConfigProvider] with no publish step. The XposedService publish is a best-effort
     * mirror for the remote-prefs read path and its failure no longer fails the save.
     */
    fun save(context: Context, state: MockState): Boolean {
        val local = localPrefs(context)
        val okLocal = local.edit().putString(Constants.K_STATE, state.toJson()).commit()
        val okRemote = publishToRemote(local)
        return okLocal && (okRemote || service == null) // unbound = provider channel serves it
    }

    /** App-side log-switch write. Same contract as [save]. */
    fun setLogEnabled(context: Context, value: Boolean): Boolean {
        val local = localPrefs(context)
        val okLocal = local.edit().putBoolean(Constants.K_LOG, value).commit()
        val okRemote = publishToRemote(local)
        return okLocal && (okRemote || service == null)
    }

    /**
     * Block up to [timeoutMs] for the pushed binder. Covers the cold-start race where the
     * daemon's SEND_BINDER lands a few hundred ms after the user's first tap. Must be called
     * off the main thread.
     */
    private fun awaitService(timeoutMs: Long = 2000): XposedService? {
        if (service != null) return service
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (service == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        return service
    }

    private fun publishToRemote(local: SharedPreferences): Boolean = try {
        val bound = service
            ?: awaitService()
            ?: throw IllegalStateException("XposedService not bound (LSPosed 未推送服务)")
        val editor = bound.getRemotePreferences(Constants.PREFS).edit()
        editor.putString(Constants.K_STATE, local.getString(Constants.K_STATE, null))
        editor.putBoolean(Constants.K_LOG, local.getBoolean(Constants.K_LOG, false))
        if (!editor.commit()) throw IllegalStateException("remote commit returned false")
        lastPublishError = null
        true
    } catch (t: Throwable) {
        lastPublishError = t.javaClass.simpleName + ": " + t.message
        false
    }
}
