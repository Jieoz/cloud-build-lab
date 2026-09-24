package com.jieoz.rimetmock

import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.SystemClock
import android.telephony.CellInfo
import android.util.Log
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.cos
import kotlin.math.sin

/**
 * Clean-room reimplementation of com.fuck.android.rimet's location behaviour on libxposed 102.
 *
 * Feature parity with the original, minus only auto reverse-geocoding (which required the bundled
 * AMap SDK + an API key). We bundle NO SDK: every AMap type is resolved from the host (DingTalk)
 * classloader and the fake AMapLocation is built by reflection against the host's own class.
 *
 *  - getLastKnownLocation()  -> a fully-populated host AMapLocation (all ~24 fields)
 *  - setLocationListener()   -> wraps the listener; every pushed fix is rewritten to the target
 *                               (all fields), with optional ~0.1 m jitter, before the host sees it
 *  - WiFi/cell (when maskEnv) -> replays the profile's *captured, self-consistent* snapshot
 *                               (marshalled ScanResult/WifiInfo/CellInfo), not an empty list
 */
object LocationSpoofer {

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        val cl = param.classLoader ?: return
        installAMapHooks(cl)
        installEnvHooks()
    }

    private fun profile(): Profile? {
        val s = ModulePrefs.state()
        return if (s.enabled) s.active else null
    }

    // ---- AMap location hooks (resolved from host classloader) ----------------------------

    private fun installAMapHooks(cl: ClassLoader) {
        val clientCls = try {
            cl.loadClass(Constants.CLS_AMAP_CLIENT)
        } catch (_: ClassNotFoundException) {
            log("AMap SDK not present in host; location hook skipped")
            return
        }

        runCatching {
            val m = clientCls.getMethod("getLastKnownLocation")
            HookBridge.hook(m) { call ->
                val p = profile() ?: return@hook
                buildFakeLocation(cl, p)?.let {
                    call.result = it
                    log("getLastKnownLocation() replaced")
                }
            }
        }.onFailure { log("hook getLastKnownLocation failed: ${it.message}") }

        runCatching {
            val listenerCls = cl.loadClass(Constants.CLS_AMAP_LISTENER)
            val m = clientCls.getMethod("setLocationListener", listenerCls)
            HookBridge.hook(m) { call ->
                if (profile() == null) return@hook
                val original = call.args.getOrNull(0) ?: return@hook
                call.args[0] = Proxy.newProxyInstance(
                    cl, arrayOf(listenerCls), SpoofingListener(cl, original)
                )
                log("setLocationListener() wrapped")
            }
        }.onFailure { log("hook setLocationListener failed: ${it.message}") }
    }

    /** Rewrites every AMapLocation the SDK pushes, then delegates to the real listener. */
    private class SpoofingListener(
        private val cl: ClassLoader,
        private val delegate: Any
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "onLocationChanged" && args != null && args.isNotEmpty()) {
                val loc = args[0]
                val p = profile()
                if (loc is Location && p != null) applyAll(cl, loc, p, jitter = p.jitter)
            }
            return method.invoke(delegate, *(args ?: emptyArray()))
        }
    }

    /**
     * Build a fresh host-typed AMapLocation carrying the full target profile. AMapLocation extends
     * android.location.Location; base fields go through the Location API, AMap-only fields via the
     * host class's own setters (reflected, best-effort — a missing setter never aborts the fix).
     */
    private fun buildFakeLocation(cl: ClassLoader, p: Profile): Location? = runCatching {
        val cls = cl.loadClass(Constants.CLS_AMAP_LOCATION)
        val loc = cls.getConstructor(String::class.java).newInstance("lbs") as Location
        applyAll(cl, loc, p, jitter = false)
        loc
    }.getOrElse {
        log("buildFakeLocation failed: ${it.message}")
        null
    }

    /** Apply target coordinate + all AMap address fields onto a Location instance. */
    private fun applyAll(cl: ClassLoader, loc: Location, p: Profile, jitter: Boolean) {
        var lat = p.latitude
        var lng = p.longitude
        if (jitter) {
            // ~0.1 m great-circle offset in a random direction, matching the original.
            val u = Math.random()
            val v = Math.random()
            val dLat = Math.toDegrees(Constants.JITTER_RAD * (2 * u - 1))
            val dLng = Math.toDegrees(
                Math.asin(sin(Constants.JITTER_RAD) / cos(Math.toRadians(lat))) * (2 * v - 1)
            )
            lat += dLat
            lng += dLng
        }
        loc.latitude = lat
        loc.longitude = lng
        loc.accuracy = p.accuracy
        loc.altitude = p.altitude
        loc.bearing = p.bearing
        loc.speed = p.speed
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        // AMap-only string/int fields — reflected on the host class, best-effort.
        val setters = listOf(
            "setAddress" to p.address, "setCountry" to p.country, "setProvince" to p.province,
            "setCity" to p.city, "setCityCode" to p.cityCode, "setDistrict" to p.district,
            "setAdCode" to p.adCode, "setStreet" to p.street, "setStreetNum" to p.streetNum,
            "setPoiName" to p.poiName, "setAoiName" to p.aoiName, "setFloor" to p.floor,
        )
        for ((name, value) in setters) {
            if (value.isEmpty()) continue
            runCatching {
                loc.javaClass.getMethod(name, String::class.java).invoke(loc, value)
            }
        }
        runCatching {
            loc.javaClass.getMethod("setLocationType", Int::class.javaPrimitiveType)
                .invoke(loc, p.locationType)
        }
        runCatching {
            loc.javaClass.getMethod("setCoordType", String::class.java).invoke(loc, p.coordType)
        }
        // A real fix carries no error; force success so callers trust it.
        runCatching {
            loc.javaClass.getMethod("setErrorCode", Int::class.javaPrimitiveType).invoke(loc, 0)
        }
    }

    // ---- Consistent radio-environment replay (framework types only) ----------------------

    private fun installEnvHooks() {
        runCatching {
            val wm = android.net.wifi.WifiManager::class.java
            HookBridge.hook(wm.getMethod("isWifiEnabled")) { c ->
                maskProfile()?.let { c.result = it.wifiEnabled }
            }
            HookBridge.hook(wm.getMethod("getScanResults")) { c ->
                maskProfile()?.let { p ->
                    c.result = ParcelCodec.decodeList(p.scanResults, ScanResult.CREATOR)
                }
            }
            HookBridge.hook(wm.getMethod("getConnectionInfo")) { c ->
                maskProfile()?.let { p ->
                    c.result = ParcelCodec.decode(p.connectionInfo, WifiInfo.CREATOR)
                }
            }
        }.onFailure { log("wifi hooks failed: ${it.message}") }

        runCatching {
            val tm = android.telephony.TelephonyManager::class.java
            HookBridge.hook(tm.getMethod("getAllCellInfo")) { c ->
                maskProfile()?.let { p ->
                    c.result = ParcelCodec.decodeList(p.cellInfos, CellInfo.CREATOR)
                }
            }
            runCatching {
                HookBridge.hook(tm.getMethod("getNetworkOperator")) { c ->
                    maskProfile()?.let { p -> if (p.operator.isNotEmpty()) c.result = p.operator }
                }
            }
        }.onFailure { log("cell hooks failed: ${it.message}") }
    }

    /** The active profile only when env-masking is on; null otherwise (host sees real radio). */
    private fun maskProfile(): Profile? = profile()?.takeIf { it.maskEnv }

    private fun log(msg: String) {
        runCatching { RimetMockModule.framework.log(Log.DEBUG, Constants.TAG, msg) }
        Log.d(Constants.TAG, msg)
    }
}
