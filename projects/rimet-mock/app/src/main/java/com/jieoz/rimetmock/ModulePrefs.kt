package com.jieoz.rimetmock

import android.content.Context
import android.content.SharedPreferences
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
 * Payload: the whole [MockState] as one JSON key, plus a separate log-switch boolean. There is
 * no world-readable XML (the file LSPosed 2.2 warns about, 2.3 removes) and no ContentProvider
 * fallback. A failed publish is returned to the caller and shown in the UI, never swallowed.
 */
object ModulePrefs {

    @Volatile
    private var framework: XposedInterface? = null

    @Volatile
    private var service: XposedService? = null

    @Volatile
    var lastPublishError: String? = null
        private set

    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
            }

            override fun onServiceDied(dead: XposedService) {
                if (service === dead) service = null
            }
        })
    }

    fun bind(base: XposedInterface) {
        framework = base
    }

    /** Host-side read: the current state, or an empty default before anything is saved. */
    fun state(): MockState = MockState.fromJson(
        framework?.getRemotePreferences(Constants.PREFS)?.getString(Constants.K_STATE, null)
    )

    /** Host-side read of the diagnostic log switch. Defaults closed (fail closed). */
    fun logEnabled(): Boolean =
        framework?.getRemotePreferences(Constants.PREFS)?.getBoolean(Constants.K_LOG, false) ?: false

    /** App-side convenience: last locally-written state (authoritative for the UI). */
    fun load(context: Context): MockState =
        MockState.fromJson(
            context.applicationContext
                .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getString(Constants.K_STATE, null)
        )

    fun logEnabledLocal(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.K_LOG, false)

    /** App-side save: local write first, then publish. False = publish failed (see [lastPublishError]). */
    fun save(context: Context, state: MockState): Boolean {
        val local = context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        local.edit().putString(Constants.K_STATE, state.toJson()).commit()
        return publishToRemote(local)
    }

    /** App-side log-switch write. Same contract as [save]. */
    fun setLogEnabled(context: Context, value: Boolean): Boolean {
        val local = context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        local.edit().putBoolean(Constants.K_LOG, value).commit()
        return publishToRemote(local)
    }

    private fun publishToRemote(local: SharedPreferences): Boolean = try {
        val bound = service ?: throw IllegalStateException("XposedService not bound")
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
