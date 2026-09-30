package com.jieoz.rimetmock

object Constants {
    const val HOST_DINGTALK = "com.alibaba.android.rimet"
    const val SELF_PACKAGE = "com.jieoz.rimetmock"

    // libxposed remote-prefs file. App writes (XposedService), host reads (hook interface).
    const val PREFS = "rimet_mock_prefs"
    // Whole state (profiles + active id + master switch) is ONE JSON key, so the remote-prefs
    // surface never drifts and there is no Parcel-version coupling like the original had.
    const val K_STATE = "state_json"
    // Diagnostic log switch, default OFF. Sampled once per host process start (read-once,
    // same contract as the other modules): off costs nothing at all, on survives until the
    // host is force-stopped even if the phone reboots mid-debugging.
    const val K_LOG = "diag_log"

    const val TAG = "RimetMock"

    // AMap types, resolved from the HOST classloader at hook time. Module bundles none.
    const val CLS_AMAP_CLIENT = "com.amap.api.location.AMapLocationClient"
    const val CLS_AMAP_LOCATION = "com.amap.api.location.AMapLocation"
    const val CLS_AMAP_LISTENER = "com.amap.api.location.AMapLocationListener"
    const val CLS_TENCENT_MANAGER = "com.tencent.map.geolocation.TencentLocationManager"
    const val CLS_TENCENT_LISTENER = "com.tencent.map.geolocation.TencentLocationListener"
    const val CLS_TENCENT_LOCATION = "com.tencent.map.geolocation.TencentLocation"
    const val CLS_FUSED = "com.google.android.gms.location.FusedLocationProviderClient"

    // ~0.1 m great-circle jitter, matching the original module's per-callback drift.
    const val JITTER_RAD = 7.848061528802386e-7
}
