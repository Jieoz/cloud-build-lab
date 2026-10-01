package com.jieoz.ctsshare

object Constants {
    const val HOST_GOOGLE = "com.google.android.googlequicksearchbox"
    const val SELF_PACKAGE = "com.jieoz.ctsshare"
    /** Circle to Search lives here; the module only acts in this process. */
    const val TARGET_PROCESS = "$HOST_GOOGLE:googleapp"

    // libxposed remote-prefs file. App writes (XposedService), host reads (hook interface).
    const val PREFS = "cts_share_prefs"
    // Diagnostic log switch, default OFF. Live: the host listens for changes, no restart needed.
    const val K_LOG = "diag_log"

    const val TAG = "CTSShareLSP"
}
