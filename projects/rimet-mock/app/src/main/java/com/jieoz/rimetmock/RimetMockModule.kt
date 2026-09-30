package com.jieoz.rimetmock

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Single entry. LSPosed instantiates this from META-INF/xposed/java_init.list.
 * Same structure as the working pixelify-lsp102 PixelifyModule.
 *
 * Log-switch contract (pixelify-verbatim): the switch is one boolean in remote prefs
 * ([Constants.K_LOG]) that the module app writes and the HOST reads here. We read it once per
 * host process at [onPackageReady] and gate [DebugLog] on it. Switch OFF => DebugLog never
 * writes a file; there is no marker file left behind.
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
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // UNCONDITIONAL framework-log probe (LSPosed module log only — no file, no MediaStore,
        // no storage perm, independent of the log switch). Proves the hook fired in the target.
        runCatching {
            log(Log.INFO, Constants.TAG,
                "onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        }
        if (!param.isFirstPackage) return

        // pixelify writes the host log from Application.onCreate. Install the hook first...
        runCatching {
            HookBridge.hook(
                Application::class.java.getDeclaredMethod("onCreate"),
                HookBridge.Before { call ->
                    val app = call.chainThis as? Application ?: return@Before
                    DebugLog.bind(app)
                    DebugLog.line("application onCreate ${param.packageName}")
                }
            )
        }.onFailure {
            runCatching { log(Log.WARN, Constants.TAG, "hook onCreate failed: ${it.message}") }
        }

        // ...then read the switch from remote prefs and gate the file log on it.
        val logOn = try {
            ModulePrefs.remote()?.getBoolean(Constants.K_LOG, false) ?: false
        } catch (t: Throwable) {
            runCatching { log(Log.WARN, Constants.TAG, "log switch read failed: ${t.message}") }
            false
        }
        DebugLog.setEnabled(logOn)
        // Confirmation goes to the LSPosed log unconditionally so the switch state is always
        // observable there; the file line below only appears when the switch is on.
        runCatching { log(Log.INFO, Constants.TAG, "log switch = $logOn for ${param.packageName}") }
        DebugLog.line("package ready ${param.packageName} logSwitch=$logOn")

        // Install AFTER the switch is applied, so install-time diagnostics honour it (off = no file).
        when (param.packageName) {
            Constants.SELF_PACKAGE -> installSelfActiveFlag(param)
            else -> LocationSpoofer.install(param)
        }
    }

    /** Flip ModuleUtils.isActive() to true inside our own app so the UI can show "active". */
    private fun installSelfActiveFlag(param: XposedModuleInterface.PackageReadyParam) {
        val cl = param.classLoader ?: return
        runCatching {
            val m = cl.loadClass("com.jieoz.rimetmock.ModuleUtils").getDeclaredMethod("isActive")
            HookBridge.hook(m) { it.result = true }
        }
    }
}
