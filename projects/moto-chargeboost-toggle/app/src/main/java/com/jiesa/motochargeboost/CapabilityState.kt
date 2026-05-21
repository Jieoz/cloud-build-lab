package com.jiesa.motochargeboost

data class CapabilityState(
    val hasDirectActivity: Boolean,
    val accessibilityEnabled: Boolean
) {
    fun canAssistToggle(): Boolean = hasDirectActivity && accessibilityEnabled
}
