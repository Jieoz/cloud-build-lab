package com.jiesa.xvideocatcher

/**
 * Self-check for module activation.
 *
 * [isModuleActive] returns false as written. When the module is loaded, the framework
 * replaces the method body so it returns true — so a false reading is a real "not
 * active", not an assumption. The method must therefore stay trivially hookable:
 * do not inline it, and do not let R8 rewrite it.
 */
object ModuleStatus {

    @JvmStatic
    fun isModuleActive(): Boolean = false
}
