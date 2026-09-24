package com.jieoz.rimetmock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Captures the device's *current, self-consistent* environment: the last known GPS fix plus the
 * real WiFi scan/connection and cell snapshot. This mirrors what the original module did with the
 * AMap SDK at "collect" time, except we read framework APIs directly and never touch a map SDK.
 *
 * The captured WiFi/cell objects are marshalled (ParcelCodec) so the exact framework objects can
 * be replayed inside the host later, keeping the fake GPS and the radio environment consistent.
 */
object EnvCapture {

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Last known fix across providers, newest wins. Null if no permission / no fix. */
    fun lastLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (p in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best
    }

    /** Marshalled real WiFi + cell snapshot, folded onto an existing profile. Best-effort. */
    @Suppress("MissingPermission")
    fun captureEnv(context: Context, into: Profile): Profile {
        var out = into
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val scans = if (hasLocationPermission(context)) {
                wm.scanResults.orEmpty().mapNotNull { ParcelCodec.encode(it) }
            } else emptyList()
            out = out.copy(
                wifiEnabled = wm.isWifiEnabled,
                scanResults = scans,
                connectionInfo = ParcelCodec.encode(wm.connectionInfo),
            )
        }
        runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val cells = if (hasLocationPermission(context) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
            ) {
                tm.allCellInfo.orEmpty().mapNotNull { ParcelCodec.encode(it) }
            } else emptyList()
            out = out.copy(cellInfos = cells, operator = tm.networkOperator ?: "")
        }
        return out
    }
}
