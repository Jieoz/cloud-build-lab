package balti.xposed.pixelifygooglephotos.lsp102

import android.app.Application
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Single entry. LSPosed instantiates this from META-INF/xposed/java_init.list.
 * The old module registered two IXposedHookLoadPackage classes; both run from [onPackageReady].
 */
class PixelifyModule : XposedModule() {

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
                    DebugLog.line("application onCreate ${param.packageName}", always = true)
                }
            )
        }.onFailure {
            DebugLog.line("bind log failed ${it.javaClass.simpleName}: ${it.message}")
        }
        val logOn = try {
            framework.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
                .getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false)
        } catch (t: Throwable) {
            DebugLog.line("log switch read failed ${t.javaClass.simpleName}: ${t.message}", always = true)
            false
        }
        DebugLog.setEnabled(logOn)
        DebugLog.line("package ready ${param.packageName} logSwitch=$logOn", always = true)
        FeatureSpoofer.install(param)
        DeviceSpoofer.install(param)
    }
}
