package com.jieoz.rimetmock

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Single config channel between the module app and the host (DingTalk) — verbatim the
 * pixelify-lsp102 pattern, no self-invented fallbacks (no ConfigProvider, no HostContext).
 *
 * libxposed exposes two preference interfaces and they are NOT interchangeable:
 *  - the module APP writes through [XposedService], bound via [XposedServiceHelper];
 *  - the HOST process reads through the hook-side [XposedInterface.getRemotePreferences].
 *
 * The whole [MockState] rides in one JSON key ([Constants.K_STATE]); the diagnostic log
 * switch is one boolean ([Constants.K_LOG]). What the UI saves through [open] is exactly what
 * the host reads. The switch state lives ONLY in remote prefs — no marker file — so turning it
 * off leaves nothing on disk that could keep generating logs.
 */
object ModulePrefs {

    @Volatile
    private var framework: XposedInterface? = null

    @Volatile
    private var service: XposedService? = null

    /** App context captured on first UI touch; enables the bind-time republish. */
    @Volatile
    private var appContext: Context? = null

    /** UI hook: fired (on a binder thread) whenever the service binds or dies. */
    @Volatile
    var onServiceChanged: (() -> Unit)? = null

    val isBound: Boolean get() = service != null

    /** Last remote-publish failure, or null after a successful publish. */
    @Volatile
    var lastPublishError: String? = null
        private set

    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                onServiceChanged?.invoke()
                republishLocal()
            }

            override fun onServiceDied(dead: XposedService) {
                if (service === dead) service = null
                onServiceChanged?.invoke()
            }
        })
    }

    /** Host side: bind the framework interface (called from onModuleLoaded). */
    fun bind(base: XposedInterface) {
        framework = base
    }

    private fun localPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    /** Hook-side remote view. Null before the module framework binds. */
    fun remote(): SharedPreferences? = framework?.getRemotePreferences(Constants.PREFS)

    /** Host-side read of the whole state. */
    fun state(): MockState = MockState.fromJson(remote()?.getString(Constants.K_STATE, null))

    /** Host-side read of the diagnostic log switch. Defaults closed (fail closed). */
    fun logEnabled(): Boolean = remote()?.getBoolean(Constants.K_LOG, false) ?: false

    /**
     * App-side editor. Writes land in the local file first (so the UI never loses them) and are
     * published to the remote store on [commit]/[apply]. [commit] returns whether the remote
     * publish happened; false means the binder has not arrived yet and the staged copy will be
     * republished automatically when it does.
     */
    fun open(context: Context): PublishingPrefs {
        val app = context.applicationContext
        if (appContext == null) appContext = app
        return PublishingPrefs(localPrefs(app))
    }

    class PublishingPrefs internal constructor(
        private val local: SharedPreferences,
    ) : SharedPreferences by local {

        override fun edit(): SharedPreferences.Editor = PublishingEditor(local.edit())

        inner class PublishingEditor(
            private val localEditor: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by localEditor {

            override fun commit(): Boolean {
                localEditor.commit()
                return publish()
            }

            override fun apply() {
                localEditor.apply()
                publish()
            }
        }
    }

    /** Push the whole config from the local file to the remote store. False = binder not here yet. */
    private fun publish(): Boolean {
        val bound = service
        if (bound == null) {
            lastPublishError = "LSPosed 服务未连接"
            return false
        }
        return try {
            val local = localPrefs(appContext!!)
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

    /** Republish whatever is staged locally the moment the binder arrives. */
    private fun republishLocal() {
        if (appContext == null) return
        Thread { publish() }.start()
    }

    /**
     * Live targets LSPosed reports for this module, keyed by process name. Empty when the
     * service has not connected. This is the only view that says whether DingTalk is actually
     * hooked — the in-process self-hook only says this app itself is injected.
     */
    fun runningTargets(): Map<String, HookedTarget> {
        val bound = service ?: return emptyMap()
        return try {
            bound.runningTargets.associateBy { it.processName }
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}
