package com.jieoz.rimetmock

import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved location profile. Mirrors the field set the original module carried on its
 * AMapLocation (all ~24 address/geo fields) plus a consistent WiFi + cell snapshot.
 *
 * Everything serializes to JSON. The framework Parcelables (ScanResult / WifiInfo / CellInfo) are
 * carried as Base64-marshalled strings via ParcelCodec, because they have no public constructors
 * and cannot be rebuilt field-by-field — this is exactly the original's approach, minus the
 * whole-Profile Parcel marshalling that made it version-fragile.
 */
data class Profile(
    val id: String,
    val name: String,

    // --- geo core ---
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 5f,
    val altitude: Double = 0.0,
    val bearing: Float = 0f,
    val speed: Float = 0f,

    // --- AMap address fields (all optional; auto-geocode is the only thing we dropped) ---
    val address: String = "",
    val country: String = "",
    val province: String = "",
    val city: String = "",
    val cityCode: String = "",
    val district: String = "",
    val adCode: String = "",
    val street: String = "",
    val streetNum: String = "",
    val poiName: String = "",
    val aoiName: String = "",
    val floor: String = "",
    val locationType: Int = 1,   // 1 == GPS-quality fix, the most-trusted value
    val coordType: String = "GCJ02",

    // --- behaviour toggles ---
    val jitter: Boolean = true,        // per-callback ~0.1 m drift like the original
    val maskEnv: Boolean = true,       // replay captured WiFi/cell instead of the real ones

    // --- consistent radio snapshot (Base64 marshalled framework objects) ---
    val wifiEnabled: Boolean = true,
    val scanResults: List<String> = emptyList(),   // marshalled ScanResult
    val connectionInfo: String? = null,            // marshalled WifiInfo
    val cellInfos: List<String> = emptyList(),      // marshalled CellInfo
    val operator: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("lat", latitude); put("lng", longitude)
        put("accuracy", accuracy.toDouble()); put("altitude", altitude)
        put("bearing", bearing.toDouble()); put("speed", speed.toDouble())
        put("address", address); put("country", country); put("province", province)
        put("city", city); put("cityCode", cityCode); put("district", district)
        put("adCode", adCode); put("street", street); put("streetNum", streetNum)
        put("poiName", poiName); put("aoiName", aoiName); put("floor", floor)
        put("locationType", locationType); put("coordType", coordType)
        put("jitter", jitter); put("maskEnv", maskEnv)
        put("wifiEnabled", wifiEnabled)
        put("scanResults", JSONArray(scanResults))
        put("connectionInfo", connectionInfo ?: JSONObject.NULL)
        put("cellInfos", JSONArray(cellInfos))
        put("operator", operator)
    }

    companion object {
        fun fromJson(o: JSONObject): Profile {
            fun arr(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
            return Profile(
                id = o.optString("id"),
                name = o.optString("name"),
                latitude = o.optDouble("lat", 0.0),
                longitude = o.optDouble("lng", 0.0),
                accuracy = o.optDouble("accuracy", 5.0).toFloat(),
                altitude = o.optDouble("altitude", 0.0),
                bearing = o.optDouble("bearing", 0.0).toFloat(),
                speed = o.optDouble("speed", 0.0).toFloat(),
                address = o.optString("address"),
                country = o.optString("country"),
                province = o.optString("province"),
                city = o.optString("city"),
                cityCode = o.optString("cityCode"),
                district = o.optString("district"),
                adCode = o.optString("adCode"),
                street = o.optString("street"),
                streetNum = o.optString("streetNum"),
                poiName = o.optString("poiName"),
                aoiName = o.optString("aoiName"),
                floor = o.optString("floor"),
                locationType = o.optInt("locationType", 1),
                coordType = o.optString("coordType", "GCJ02"),
                jitter = o.optBoolean("jitter", true),
                maskEnv = o.optBoolean("maskEnv", true),
                wifiEnabled = o.optBoolean("wifiEnabled", true),
                scanResults = arr("scanResults"),
                connectionInfo = o.optString("connectionInfo").ifEmpty { null }
                    .takeIf { o.has("connectionInfo") && !o.isNull("connectionInfo") },
                cellInfos = arr("cellInfos"),
                operator = o.optString("operator"),
            )
        }
    }
}

/** Full app state: the profile list, which one is active, and the master enable switch. */
data class MockState(
    val enabled: Boolean = false,
    val activeId: String? = null,
    val profiles: List<Profile> = emptyList(),
) {
    val active: Profile? get() = profiles.firstOrNull { it.id == activeId }

    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("activeId", activeId ?: JSONObject.NULL)
        put("profiles", JSONArray(profiles.map { it.toJson() }))
    }.toString()

    companion object {
        fun fromJson(s: String?): MockState {
            if (s.isNullOrEmpty()) return MockState()
            return runCatching {
                val o = JSONObject(s)
                val arr = o.optJSONArray("profiles")
                val list = if (arr == null) emptyList()
                else (0 until arr.length()).map { Profile.fromJson(arr.getJSONObject(it)) }
                MockState(
                    enabled = o.optBoolean("enabled", false),
                    activeId = if (o.isNull("activeId")) null else o.optString("activeId"),
                    profiles = list,
                )
            }.getOrElse { MockState() }
        }
    }
}
