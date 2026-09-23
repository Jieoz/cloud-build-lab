package balti.xposed.pixelifygooglephotos

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
        FeatureSpoofer.install(param)
        DeviceSpoofer.install(param)
    }
}
