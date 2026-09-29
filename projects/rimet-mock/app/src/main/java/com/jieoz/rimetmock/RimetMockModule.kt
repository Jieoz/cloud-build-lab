package com.jieoz.rimetmock

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Single entry. LSPosed instantiates this from META-INF/xposed/java_init.list.
 *
 * Clean-room reimplementation of com.fuck.android.rimet on libxposed API 102: legacy
 * IXposedHookLoadPackage + assets/xposed_init + XSharedPreferences are all gone, and no
 * third-party SDK is bundled (AMap types are resolved from the host classloader at hook time).
 *
 * The diagnostic log switch is sampled ONCE here (read-once, same contract as the sibling
 * modules): off costs nothing — no queue, no thread, no disk write. A change applies after
 * DingTalk is force-stopped and reopened (the UI says so). Sampling at process start also
 * means a mid-session reboot cannot silently flip an active debug session back to silent.
 */
class RimetMockModule : XposedModule() {

    companion object {
        @Volatile
        lateinit var framework: XposedInterface
            private set
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        framework = this
        ModulePrefs.bind(this)
        log("module loaded")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return

        // Diagnostic file logging: bind the sink when the host Application exists (earlier
        // lines are queued), then sample the switch once. Runtime hook paths route their
        // trace lines through DebugLog.line, so off = one boolean check per callback.
        runCatching {
            HookBridge.hook(
                Application::class.java.getDeclaredMethod("onCreate"),
                HookBridge.Before { call ->
                    val app = call.chainThis as? Application ?: return@Before
                    DebugLog.bind(app)
                    DebugLog.line("application onCreate ${param.packageName}")
                }
            )
        }.onFailure { log("bind log sink failed: ${it.message}") }

        val logOn = try {
            ModulePrefs.logEnabled()
        } catch (t: Throwable) {
            log("log switch read failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        DebugLog.setEnabled(logOn)
        DebugLog.line("package ready ${param.packageName} logSwitch=$logOn")

        when (param.packageName) {
            Constants.SELF_PACKAGE -> installSelfActiveFlag(param)
            Constants.HOST_DINGTALK -> LocationSpoofer.install(param)
        }
    }

    /** Flip ModuleUtils.isActive() to true inside our own app so the UI can show "active". */
    private fun installSelfActiveFlag(param: XposedModuleInterface.PackageReadyParam) {
        val cl = param.classLoader ?: return
        runCatching {
            val m = cl.loadClass("com.jieoz.rimetmock.ModuleUtils").getDeclaredMethod("isActive")
            HookBridge.hook(m) { it.result = true }
            log("self isActive() hooked -> true")
        }.onFailure { log("self hook failed: ${it.message}") }
    }

    /** Install-time/LSPosed channel log: always on, once per process, negligible cost. */
    private fun log(msg: String) {
        runCatching { framework.log(Log.DEBUG, Constants.TAG, msg) }
        Log.d(Constants.TAG, msg)
    }
}
