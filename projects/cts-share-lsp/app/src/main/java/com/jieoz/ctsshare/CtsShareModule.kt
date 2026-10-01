package com.jieoz.ctsshare

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed (libxposed API 102) entry. Replaces the upstream Zygisk loader
 * (Entermage/cts-selection-share jni/main.cpp): same target process, same
 * ShareBootstrap.init(Application) hand-off, no native code or dex loading.
 */
class CtsShareModule : XposedModule() {

    private var processName: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        when (param.packageName) {
            SELF_PACKAGE -> markSelfActive(param.classLoader)
            GOOGLE_PACKAGE -> if (processName == TARGET_PROCESS) hookApplication()
        }
    }

    /** Upstream waits for ActivityThread.currentApplication(); hooking onCreate is the direct form. */
    private fun hookApplication() {
        runCatching {
            hook(Application::class.java.getDeclaredMethod("onCreate")).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Application)?.let { app ->
                    runCatching { ShareBootstrap.init(app) }
                        .onFailure { log(Log.ERROR, TAG, "init failed", it) }
                }
                result
            }
            log(Log.INFO, TAG, "hooked $TARGET_PROCESS")
        }.onFailure { log(Log.ERROR, TAG, "hook Application.onCreate failed", it) }
    }

    private fun markSelfActive(classLoader: ClassLoader) {
        runCatching {
            val method = classLoader.loadClass("$SELF_PACKAGE.MainActivity")
                .getDeclaredMethod("isModuleActive")
            hook(method).intercept { true }
        }
    }

    companion object {
        private const val TAG = "CTSShareLSP"
        private const val SELF_PACKAGE = "com.jieoz.ctsshare"
        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val TARGET_PROCESS = "$GOOGLE_PACKAGE:googleapp"
    }
}
