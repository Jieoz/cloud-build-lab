package com.jiesa.motochargeboost

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChargeBoostAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AssistState.pendingToggle) return
        if (event == null) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.motorola.coresettingsext") return

        if (tryClickByText(root, "疾速快充") || tryClickSwitchNearText(root, "疾速快充")) {
            AssistState.pendingToggle = false
        }
    }

    override fun onInterrupt() = Unit

    private fun tryClickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (clickNodeOrParent(node)) return true
        }
        return false
    }

    private fun tryClickSwitchNearText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (label in nodes) {
            val parent = label.parent ?: continue
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                val className = child.className?.toString().orEmpty()
                val textValue = child.text?.toString().orEmpty()
                val descValue = child.contentDescription?.toString().orEmpty()
                if (
                    className.contains("Switch", ignoreCase = true) ||
                    className.contains("CheckBox", ignoreCase = true) ||
                    descValue.contains(text, ignoreCase = true) ||
                    textValue.contains(text, ignoreCase = true)
                ) {
                    if (clickNodeOrParent(child)) return true
                }
            }
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val target = current ?: return false
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = target.parent
        }
        return false
    }
}
