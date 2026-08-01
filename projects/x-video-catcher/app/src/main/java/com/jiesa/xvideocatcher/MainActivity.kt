package com.jiesa.xvideocatcher

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The module UI. Two jobs: say whether the probe is active, and point at the log file.
 *
 * It deliberately does *not* read or export the log. The log is written by X's process
 * into shared Downloads, and Android only shows a non-media file in Downloads to the
 * app that created it — so this app cannot open it, with or without permissions. The
 * previous design pretended otherwise (module-side provider + in-app export) and could
 * only ever show zero records.
 *
 * Everything is built in code — a probe build does not warrant layout XML.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 20f
            }
        )

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, pad)
            setTextIsSelectable(true)
        }
        root.addView(statusView)

        root.addView(button(getString(R.string.action_open)) { openDownloads() })
        root.addView(button(getString(R.string.action_refresh)) { refresh() })

        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    /**
     * `isModuleActive` is rewritten by the framework when the module is loaded, so a
     * false reading here is a genuine "not active" rather than a guess.
     *
     * Note this reflects *this* process being hooked. It does not prove the hook
     * attached inside X — the log file existing is what proves that.
     */
    private fun refresh() {
        val active = ModuleStatus.isModuleActive()
        val hostVersion = runCatching {
            packageManager.getPackageInfo("com.twitter.android", 0).versionName
        }.getOrNull() ?: "not installed"

        statusView.text = buildString {
            append(getString(if (active) R.string.status_active else R.string.status_inactive))
            append("\n\n")
            append(getString(R.string.log_location, ProbeSink.displayPath()))
            append("\n\n")
            append(getString(R.string.log_hint))
            append("\n\n")
            append(getString(R.string.log_note))
            append("\n\n")
            append("module=${BuildConfig.VERSION_NAME}\n")
            append("host=com.twitter.android $hostVersion\n")
            append("device=${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        }
    }

    /**
     * ACTION_VIEW_DOWNLOADS is the one entry point that exists on every OEM build;
     * a direct ACTION_VIEW on the folder Uri is refused on many of them.
     */
    private fun openDownloads() {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.toast_no_file_manager)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
