package com.jieoz.rimetmock

object Constants {
    const val HOST_DINGTALK = "com.alibaba.android.rimet"
    const val SELF_PACKAGE = "com.jieoz.rimetmock"

    // libxposed remote-prefs file. App writes, host reads.
    const val PREFS = "rimet_mock_prefs"
    // Whole state (profiles + active id + master switch) is ONE JSON key, so the remote-prefs
    // surface never drifts and there is no Parcel-version coupling like the original had.
    const val K_STATE = "state_json"

    const val TAG = "RimetMock"

    // AMap types, resolved from the HOST classloader at hook time. Module bundles none.
    const val CLS_AMAP_CLIENT = "com.amap.api.location.AMapLocationClient"
    const val CLS_AMAP_LOCATION = "com.amap.api.location.AMapLocation"
    const val CLS_AMAP_LISTENER = "com.amap.api.location.AMapLocationListener"

    // ~0.1 m great-circle jitter, matching the original module's per-callback drift.
    const val JITTER_RAD = 7.848061528802386e-7
}
