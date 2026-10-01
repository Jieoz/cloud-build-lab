package com.jieoz.ctsshare

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Single entry, same structure as yt-translate-probe / rimet-mock / pixelify-lsp102.
 *
 * Function: replaces the upstream Zygisk loader (Entermage/cts-selection-share jni/main.cpp).
 * Same target process, same ShareBootstrap.init(Application) hand-off after Application.onCreate.
 *
 * Log switch: one boolean in remote prefs ([Constants.K_LOG]); the module app writes it, Google
 * reads it here and a remote-prefs listener flips [DebugLog] live, so toggling needs no restart.
 */
class CtsShareModule : XposedModule() {

    companion object {
        @Volatile
        lateinit var framework: XposedInterface
            private set

        // Strong ref: SharedPreferences holds listeners weakly.
        private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    }

    private var processName: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        framework = this
        processName = param.processName
        ModulePrefs.bind(this)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        runCatching {
            log(Log.INFO, Constants.TAG, "onPackageReady pkg=${param.packageName} proc=$processName first=${param.isFirstPackage}")
        }
        if (!param.isFirstPackage) return
        when (param.packageName) {
            Constants.SELF_PACKAGE -> installSelfActiveFlag(param)
            Constants.HOST_GOOGLE -> if (processName == Constants.TARGET_PROCESS) installHost(param)
        }
    }

    private fun installHost(param: XposedModuleInterface.PackageReadyParam) {
        runCatching {
            framework.hook(Application::class.java.getDeclaredMethod("onCreate")).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Application)?.let { app ->
                    DebugLog.bind(app)
                    DebugLog.line("application onCreate ${param.packageName} proc=$processName")
                    runCatching { ShareBootstrap.init(app) }.onFailure {
                        runCatching { log(Log.ERROR, Constants.TAG, "ShareBootstrap.init failed", it) }
                        DebugLog.line("ShareBootstrap.init failed ${Log.getStackTraceString(it)}")
                    }
                }
                result
            }
        }.onFailure {
            runCatching { log(Log.WARN, Constants.TAG, "hook onCreate failed: ${it.message}") }
        }

        val prefs = ModulePrefs.remote()
        val logOn = try {
            prefs?.getBoolean(Constants.K_LOG, false) ?: false
        } catch (t: Throwable) {
            runCatching { log(Log.WARN, Constants.TAG, "log switch read failed: ${t.message}") }
            false
        }
        DebugLog.setEnabled(logOn)
        runCatching { log(Log.INFO, Constants.TAG, "log switch = $logOn for $processName") }
        DebugLog.line("package ready ${param.packageName} proc=$processName logSwitch=$logOn")

        if (prefs != null) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key != Constants.K_LOG) return@OnSharedPreferenceChangeListener
                val on = runCatching { p.getBoolean(Constants.K_LOG, false) }.getOrDefault(false)
                val was = DebugLog.isOn
                DebugLog.setEnabled(on)
                runCatching { log(Log.INFO, Constants.TAG, "log switch live -> $on") }
                if (on && !was) DebugLog.line("log switch live -> on proc=$processName")
            }
            listener = l
            runCatching { prefs.registerOnSharedPreferenceChangeListener(l) }
        }
    }

    private fun installSelfActiveFlag(param: XposedModuleInterface.PackageReadyParam) {
        val cl = param.classLoader ?: return
        runCatching {
            val m = cl.loadClass("com.jieoz.ctsshare.ModuleUtils").getDeclaredMethod("isActive")
            HookBridge.hook(m) { it.result = true }
        }
    }
}
