package com.jiesa.motochargeboost

import android.content.Context
import android.view.accessibility.AccessibilityManager

object CapabilityDetector {
    fun detect(context: Context): CapabilityState {
        val pm = context.packageManager
        val direct = runCatching { pm.getActivityInfo(TargetActivities.chargeBoost, 0); true }.getOrDefault(false)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityEnabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        return CapabilityState(direct, accessibilityEnabled)
    }
}
