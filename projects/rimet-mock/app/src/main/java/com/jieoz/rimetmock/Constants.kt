package com.jieoz.rimetmock

object Constants {
    const val HOST_DINGTALK = "com.alibaba.android.rimet"

    // libxposed remote-prefs file name. Written by the app UI, read inside the host.
    const val PREFS = "rimet_mock_prefs"

    const val K_ENABLED = "enabled"
    const val K_LAT = "lat"          // stored as String to keep full double precision
    const val K_LNG = "lng"
    const val K_ACCURACY = "accuracy" // meters, String
    const val K_ALTITUDE = "altitude" // meters, String
    const val K_MASK_ENV = "mask_env"  // neutralise WiFi/cell cross-check
    const val K_VERBOSE = "verbose"

    const val TAG = "RimetMock"

    // AMap SDK class names, resolved from the HOST classloader at hook time.
    // The module itself bundles none of these.
    const val CLS_AMAP_CLIENT = "com.amap.api.location.AMapLocationClient"
    const val CLS_AMAP_LOCATION = "com.amap.api.location.AMapLocation"
    const val CLS_AMAP_LISTENER = "com.amap.api.location.AMapLocationListener"
}
