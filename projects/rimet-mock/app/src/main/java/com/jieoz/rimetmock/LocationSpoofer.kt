package com.jieoz.rimetmock

import android.location.Location
import android.util.Log
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Clean-room reimplementation of com.fuck.android.rimet's location behaviour.
 *
 * Design difference from the original module:
 *  - The original **bundled the AMap location SDK** (269 classes) and needed an AMap API key,
 *    because its own UI captured a real fix once. We bundle NO SDK: every AMap type is resolved
 *    from the **host (DingTalk) classloader** at hook time, and the spoofed AMapLocation is
 *    built by reflection against the host's own copy of the class. Nothing proprietary ships in
 *    this APK, so there is no key and no SDK-version coupling.
 *  - The original hooked getLastKnownLocation (returns a fixed fake) and setLocationListener
 *    (wraps the listener, offsetting coordinates by a random ~0.1 m jitter). We do the same two,
 *    reading the target lat/lng from libxposed remote prefs instead of a Parcelled Profile.
 *
 * WiFi/cell masking is optional (K_MASK_ENV): returning empty scan/cell results stops a host
 * from cross-checking the fake GPS against the real radio environment. It uses framework
 * WifiManager/TelephonyManager only — no third-party types.
 */
object LocationSpoofer {

    private lateinit var host: XposedModuleInterface.PackageReadyParam

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        host = param
        val cl = param.classLoader ?: return

        installAMapHooks(cl)
        if (prefBool(Constants.K_MASK_ENV, false)) installEnvMask(cl)
    }

    // ---- AMap location hooks (resolved from host classloader) ----------------------------

    private fun installAMapHooks(cl: ClassLoader) {
        val clientCls = try {
            cl.loadClass(Constants.CLS_AMAP_CLIENT)
        } catch (_: ClassNotFoundException) {
            log("AMap SDK not present in host; location hook skipped")
            return
        }

        // AMapLocationClient#getLastKnownLocation() -> return a fully-built fake AMapLocation.
        runCatching {
            val m = clientCls.getMethod("getLastKnownLocation")
            HookBridge.hook(m) { call ->
                if (!enabled()) return@hook
                buildFakeLocation(cl)?.let {
                    call.result = it
                    log("getLastKnownLocation() replaced")
                }
            }
        }.onFailure { log("hook getLastKnownLocation failed: ${it.message}") }

        // AMapLocationClient#setLocationListener(AMapLocationListener) -> wrap the listener so
        // every pushed fix is rewritten to the target coordinate before the host sees it.
        runCatching {
            val listenerCls = cl.loadClass(Constants.CLS_AMAP_LISTENER)
            val m = clientCls.getMethod("setLocationListener", listenerCls)
            HookBridge.hook(m) { call ->
                if (!enabled()) return@hook
                val original = call.args.getOrNull(0) ?: return@hook
                val proxy = Proxy.newProxyInstance(
                    cl, arrayOf(listenerCls), SpoofingListener(cl, original)
                )
                call.args[0] = proxy
                // do not swallow: let the original setter run with our wrapped listener
                log("setLocationListener() wrapped")
            }
        }.onFailure { log("hook setLocationListener failed: ${it.message}") }
    }

    /** Rewrites lat/lng on each AMapLocation the SDK pushes, then delegates to the real listener. */
    private class SpoofingListener(
        private val cl: ClassLoader,
        private val delegate: Any
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "onLocationChanged" && args != null && args.isNotEmpty()) {
                (args[0] as? Location)?.let { applyTarget(it) }
            }
            return method.invoke(delegate, *(args ?: emptyArray()))
        }
    }

    /**
     * Build a fresh host-typed AMapLocation carrying the target coordinate. AMapLocation extends
     * android.location.Location, so provider/lat/lng/accuracy/time go through the Location API;
     * AMap-specific fields (locationType, address) are set reflectively when present.
     */
    private fun buildFakeLocation(cl: ClassLoader): Location? {
        val target = target() ?: return null
        return runCatching {
            val cls = cl.loadClass(Constants.CLS_AMAP_LOCATION)
            val loc = cls.getConstructor(String::class.java).newInstance("lbs") as Location
            applyTarget(loc)
            // AMapLocation.setLocationType(int): 1 == GPS-quality fix, most trusted by callers.
            runCatching {
                cls.getMethod("setLocationType", Int::class.javaPrimitiveType).invoke(loc, 1)
            }
            loc
        }.getOrElse {
            log("buildFakeLocation failed: ${it.message}")
            null
        }
    }

    /** Writes target lat/lng (+ optional accuracy/altitude) onto any Location instance. */
    private fun applyTarget(loc: Location) {
        val lat = prefDouble(Constants.K_LAT) ?: return
        val lng = prefDouble(Constants.K_LNG) ?: return
        loc.latitude = lat
        loc.longitude = lng
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        prefDouble(Constants.K_ACCURACY)?.let { loc.accuracy = it.toFloat() }
            ?: run { loc.accuracy = 5f }
        prefDouble(Constants.K_ALTITUDE)?.let { loc.altitude = it }
    }

    // ---- Optional radio-environment masking (framework types only) -----------------------

    private fun installEnvMask(cl: ClassLoader) {
        runCatching {
            val wm = android.net.wifi.WifiManager::class.java
            HookBridge.hook(wm.getMethod("getScanResults")) { c ->
                if (enabled()) c.result = emptyList<android.net.wifi.ScanResult>()
            }
            HookBridge.hook(wm.getMethod("getConnectionInfo")) { c ->
                if (enabled()) c.result = null
            }
        }.onFailure { log("wifi mask failed: ${it.message}") }

        runCatching {
            val tm = android.telephony.TelephonyManager::class.java
            HookBridge.hook(tm.getMethod("getAllCellInfo")) { c ->
                if (enabled()) c.result = emptyList<android.telephony.CellInfo>()
            }
        }.onFailure { log("cell mask failed: ${it.message}") }
    }

    // ---- prefs helpers -------------------------------------------------------------------

    private fun enabled() = prefBool(Constants.K_ENABLED, false)

    private fun target(): Pair<Double, Double>? {
        val lat = prefDouble(Constants.K_LAT) ?: return null
        val lng = prefDouble(Constants.K_LNG) ?: return null
        return lat to lng
    }

    private fun prefBool(key: String, def: Boolean): Boolean =
        ModulePrefs.remote()?.getBoolean(key, def) ?: def

    private fun prefDouble(key: String): Double? =
        ModulePrefs.remote()?.getString(key, null)?.toDoubleOrNull()

    private fun log(msg: String) {
        runCatching { RimetMockModule.framework.log(Log.DEBUG, Constants.TAG, msg) }
        Log.d(Constants.TAG, msg)
    }
}
