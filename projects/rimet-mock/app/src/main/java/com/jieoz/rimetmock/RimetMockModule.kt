package com.jieoz.rimetmock

import android.app.Application
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Single entry. LSPosed instantiates this from META-INF/xposed/java_init.list.
 * Same structure as pixelify-lsp102's PixelifyModule.
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
        if (!param.isFirstPackage) return
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
            DebugLog.line("bind log failed ${it.javaClass.simpleName}: ${it.message}")
        }
        val logOn = try {
            ModulePrefs.remote()?.getBoolean(Constants.K_LOG, false) ?: false
        } catch (t: Throwable) {
            DebugLog.line("log switch read failed ${t.javaClass.simpleName}: ${t.message}")
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
            DebugLog.line("self isActive() hooked -> true")
        }.onFailure { DebugLog.line("self hook failed ${it.message}") }
    }
}
