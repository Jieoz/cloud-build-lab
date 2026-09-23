package balti.xposed.pixelifygooglephotos

import android.os.Build
import android.util.Log
import balti.xposed.pixelifygooglephotos.Constants.PACKAGE_NAME_GOOGLE_PHOTOS
import balti.xposed.pixelifygooglephotos.Constants.PREF_DEVICE_TO_SPOOF
import balti.xposed.pixelifygooglephotos.Constants.PREF_ENABLE_VERBOSE_LOGS
import balti.xposed.pixelifygooglephotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import balti.xposed.pixelifygooglephotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import balti.xposed.pixelifygooglephotos.Constants.PREF_STRICTLY_CHECK_GOOGLE_PHOTOS
import io.github.libxposed.api.XposedModuleInterface

/**
 * Codenames of pixels:
 * https://oneandroid.net/all-google-pixel-codenames-from-sailfish-to-redfin/
 *
 * Device properties stored in [DeviceProps].
 */
class DeviceSpoofer {

    companion object {
        fun install(param: XposedModuleInterface.PackageReadyParam) {
            DeviceSpoofer().install(param)
        }
    }

    /**
     * Simple message to log messages in lsposed log as well as android log.
     */
    private fun log(message: String){
        PixelifyModule.framework.log(Log.DEBUG, "PixelifyGooglePhotos", message)
        Log.d("PixelifyGooglePhotos", message)
    }

    /**
     * To read preference of user. Written by the module app into libxposed remote prefs.
     */
    private val pref by lazy {
        ModulePrefs.remote()
    }

    private val verboseLog: Boolean by lazy {
        pref?.getBoolean(PREF_ENABLE_VERBOSE_LOGS, false) ?: false
    }

    /**
     * This will always be null if the user has not chosen to spoof android version.
     * If not null, then following will be spoofed:
     * [Build.VERSION.RELEASE], [Build.VERSION.SDK_INT]
     *
     * @see DeviceProps.AndroidVersion
     */
    private val androidVersionToSpoof: DeviceProps.AndroidVersion? by lazy {
        if (pref?.getBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false) == true)
            finalDeviceToSpoof?.androidVersion
        else {
            pref?.getString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)?.let {
                DeviceProps.getAndroidVersionFromLabel(it)
            }
        }
    }

    /**
     * This is the final device to spoof.
     * By default use Pixel 5.
     */
    private val finalDeviceToSpoof by lazy {
        val deviceName = pref?.getString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
        log("Device spoof: $deviceName")
        DeviceProps.getDeviceProps(deviceName)
    }

    /**
     * Inspired by:
     * https://github.com/itsuki-t/FakeDeviceData/blob/master/src/jp/rmitkt/xposed/fakedevicedata/FakeDeviceData.java
     */
    fun install(param: XposedModuleInterface.PackageReadyParam) {

        /**
         * If user selects to never use this on any other app other than Google photos,
         * then check package name and return if necessary.
         */
        if ((pref?.getBoolean(PREF_STRICTLY_CHECK_GOOGLE_PHOTOS, true) ?: true) &&
            param.packageName != PACKAGE_NAME_GOOGLE_PHOTOS) return

        log("Loaded DeviceSpoofer for ${param.packageName}")
        log("Device spoof: ${finalDeviceToSpoof?.deviceName}")

        finalDeviceToSpoof?.props?.run {

            if (keys.isEmpty()) return
            val classLoader = param.classLoader ?: return

            val classBuild = HookBridge.findClass("android.os.Build", classLoader)
            keys.forEach {
                HookBridge.setStaticObject(classBuild, it, this[it])
                if (verboseLog) log("DEVICE PROPS: $it - ${this[it]}")
            }

        }

        androidVersionToSpoof?.getAsMap()?.run {

            val classLoader = param.classLoader ?: return
            val classBuild = HookBridge.findClass("android.os.Build.VERSION", classLoader)

            keys.forEach {
                HookBridge.setStaticObject(classBuild, it, this[it])
                if (verboseLog) log("VERSION SPOOF: $it - ${this[it]}")
            }
        }

    }

}
