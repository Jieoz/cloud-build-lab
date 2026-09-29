package com.jieoz.rimetmock

import android.app.Application
import android.content.Context

/**
 * Host-side context for the provider fallback read. Captured in Application.onCreate (the
 * same hook that binds the log sink); null before that, which only matters for reads the
 * module never makes that early anyway.
 */
object HostContext {
    @Volatile
    var app: Context? = null
}
