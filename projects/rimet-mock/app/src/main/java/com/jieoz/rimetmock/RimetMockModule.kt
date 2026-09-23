package com.jieoz.rimetmock

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Single entry. LSPosed instantiates this from META-INF/xposed/java_init.list.
 *
 * Clean-room reimplementation of the com.fuck.android.rimet location-mock behaviour on the
 * libxposed API 102 surface: legacy IXposedHookLoadPackage + assets/xposed_init +
 * XSharedPreferences are all gone. This module bundles NO third-party SDK — the AMap types are
 * resolved from the host (DingTalk) classloader at hook time.
 */
class RimetMockModule : XposedModule() {

    companion object {
        @Volatile
        lateinit var framework: XposedInterface
            private set

        const val SELF_PACKAGE = "com.jieoz.rimetmock"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        framework = this
        ModulePrefs.bind(this)
        log("module loaded")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        when (param.packageName) {
            SELF_PACKAGE -> installSelfActiveFlag(param)
            Constants.HOST_DINGTALK -> LocationSpoofer.install(param)
        }
    }

    /** Flip ModuleUtils.isActive() to true inside our own app so the UI can show "active". */
    private fun installSelfActiveFlag(param: XposedModuleInterface.PackageReadyParam) {
        val cl = param.classLoader ?: return
        runCatching {
            val m = cl.loadClass("com.jieoz.rimetmock.ModuleUtils")
                .getDeclaredMethod("isActive")
            HookBridge.hook(m) { it.result = true }
            log("self isActive() hooked -> true")
        }.onFailure { log("self hook failed: ${it.message}") }
    }

    private fun log(msg: String) {
        runCatching { framework.log(Log.DEBUG, Constants.TAG, msg) }
        Log.d(Constants.TAG, msg)
    }
}
