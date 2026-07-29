package com.jiesa.xvideocatcher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

/**
 * The launcher activity exists only so the module is visible and openable from the
 * launcher. It reports whether the module is active, which is the single question
 * worth answering outside the LSPosed manager.
 *
 * `isModuleActive` stays false in the module's own process unless the framework
 * replaced it — that replacement is exactly the signal we display.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val active = isModuleActive()
        val status = buildString {
            appendLine(if (active) "Module active" else "Module NOT active")
            appendLine()
            appendLine("Target: com.twitter.android (X 12.11.1)")
            appendLine("Device profile: Android 14 / API 34")
            appendLine()
            appendLine(
                if (active) {
                    "Enable the scope for X in LSPosed, force-stop X, then play a video. " +
                        "Probe output goes to the LSPosed log and to X's own cache dir " +
                        "(files/cache/xvc-probe.jsonl inside com.twitter.android)."
                } else {
                    "Enable this module in LSPosed and reboot, then reopen this screen."
                }
            )
        }

        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 15f
            gravity = Gravity.START
            contentDescription = getString(R.string.probe_status_title)
            text = status
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    /**
     * Replaced by the framework when the module is loaded. Kept non-const and
     * unobfuscated so the hook has a stable target.
     */
    private fun isModuleActive(): Boolean = false
}
