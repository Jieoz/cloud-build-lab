package com.jieoz.ctsshare

/** Returns false; the module hooks it to true inside its own process when LSPosed is live. */
object ModuleUtils {
    @JvmStatic
    fun isActive(): Boolean = false
}
