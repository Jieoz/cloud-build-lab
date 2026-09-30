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

    // The config is a single small JSON doc. DingTalk may consult the profile on every
    // location callback (several times per second), so re-parsing JSON per call is wasted
    // work. Cache with a short TTL; the underlying read is either an in-memory remote-prefs
    // mirror or a binder-free provider query — both cheap, and a 1s TTL keeps edits snappy.
    private const val STATE_TTL_MS = 1_000L

    // MUST stay 0, not Long.MIN_VALUE. The refresh test is `now - cachedAt >= TTL`.
    // Long.MIN_VALUE underflows that subtraction to a large negative, so the first
    // state() (taken at install, which DID see the saved profile) is frozen forever
    // and every later hook callback treats the profile as absent.
    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var cachedEnabled: Boolean = false

    @Volatile
    private var cachedActive: Profile? = null

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        val cl = param.classLoader
        if (cl == null) {
            log("install: classLoader is NULL; nothing hookable in this process")
            return
        }
        log("install: begin, resolving AMap classes from host classloader")
        installAMapHooks(cl)
        installEnvHooks()
        log("install: done")
    }

    private fun profile(): Profile? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt >= STATE_TTL_MS) {
            val s = state()
            cachedEnabled = s.enabled
            cachedActive = s.active
            cachedAt = now
        }
        return if (cachedEnabled) cachedActive else null
    }

    /** Host-side read of the spoof config from remote prefs. Logged once per refresh so the file
     *  shows whether DingTalk actually received the saved profile (the gap the two-line log hid). */
    @Volatile
    private var lastLoggedConfig: String? = null

    private fun state(): MockState {
        val s = ModulePrefs.state()
        val a = s.active
        val desc = "config read: enabled=${s.enabled} active=${a?.name ?: "none"} " +
            "lat=${a?.latitude} lng=${a?.longitude} maskEnv=${a?.maskEnv}"
        if (desc != lastLoggedConfig) {
            lastLoggedConfig = desc
            DebugLog.line(desc)
        }
        return s
    }

    /** Diagnostics into the file the user reads. Gated by the log switch (always=false): off means
     *  no file at all. */
    private fun log(msg: String) {
        DebugLog.line(msg)
        Log.d(Constants.TAG, msg)
    }

    // ---- AMap location hooks (resolved from host classloader) ----------------------------

    private fun installAMapHooks(cl: ClassLoader) {
        val clientCls = try {
            cl.loadClass(Constants.CLS_AMAP_CLIENT)
        } catch (_: ClassNotFoundException) {
            log("AMap SDK NOT present in host (${Constants.CLS_AMAP_CLIENT} missing); location hook skipped")
            return
        }
        log("AMap client class FOUND: ${Constants.CLS_AMAP_CLIENT}")
        // First config read AT INSTALL TIME — this is evidence point #1: does the host actually
        // see the profile the module app saved? Never gated on a hook being called later.
        state()

        runCatching {
            val m = clientCls.getMethod("getLastKnownLocation")
            HookBridge.hook(m) { call ->
                val p = profile()
                if (p == null) {
                    DebugLog.line("getLastKnownLocation() called; no active profile, passing through")
                    return@hook
                }
                buildFakeLocation(cl, p)?.let {
                    call.result = it
                    DebugLog.line("getLastKnownLocation() replaced (${p.name}) lat=${it.latitude} lng=${it.longitude}")
                } ?: DebugLog.line("getLastKnownLocation() buildFakeLocation FAILED")
            }
        }.onFailure { log("hook getLastKnownLocation failed: ${it.message}") }

        runCatching {
            val listenerCls = cl.loadClass(Constants.CLS_AMAP_LISTENER)
            val m = clientCls.getMethod("setLocationListener", listenerCls)
            HookBridge.hook(m) { call ->
                if (profile() == null) {
                    DebugLog.line("setLocationListener called but no active profile; not wrapping")
                    return@hook
                }
                val original = call.args.getOrNull(0) ?: return@hook
                call.args[0] = Proxy.newProxyInstance(
                    cl, arrayOf(listenerCls), SpoofingListener(cl, original)
                )
                DebugLog.line("setLocationListener() wrapped")
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
                if (loc is Location && p != null) {
                    applyAll(cl, loc, p, jitter = p.jitter)
                    DebugLog.line("onLocationChanged rewritten (${p.name}, jitter=${p.jitter})")
                }
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
        DebugLog.line("buildFakeLocation failed: ${it.message}")
        null
    }

    /** Apply target coordinate + all AMap address fields onto a Location instance. */
    private fun applyAll(cl: ClassLoader, loc: Location, p: Profile, jitter: Boolean) {
        var lat = p.latitude
        var lng = p.longitude
        // Host-side E6 self-heal: the same micro-degree case the editor now rejects, defended here
        // so an old bad profile still yields a usable fix instead of an impossible one.
        if (kotlin.math.abs(lat) > 90.0) lat /= 1_000_000.0
        if (kotlin.math.abs(lng) > 180.0) lng /= 1_000_000.0
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
                    c.result = ParcelCodec.decodeReflect(p.connectionInfo, WifiInfo::class.java)
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
}
