package com.jieoz.rimetmock

/**
 * Module-active self check, mirroring the original com.fuck.android.rimet.util.ModuleUtils.
 *
 * The method body returns false. When LSPosed has the module active, [RimetMockModule] is loaded
 * into THIS app's own process (self is in scope.list) and hooks [isActive] to return true. So a
 * true result means "the framework is live and hooking me" — a genuine activation signal, not a
 * guess. When the module is not enabled, nothing hooks it and it stays false.
 */
object ModuleUtils {
    @JvmStatic
    fun isActive(): Boolean = false
}
