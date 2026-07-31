package com.jiesa.xvideocatcher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The module UI. Two jobs: say whether the probe is active, and get the collected log
 * off the device with one tap.
 *
 * Everything is built in code — a probe build does not warrant layout XML, and this
 * keeps the whole screen readable in one file.
 */
class MainActivity : Activity() {

    private lateinit var store: ProbeStore
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProbeStore(this)

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

        root.addView(
            button(getString(R.string.action_share)) { shareLog() }
        )
        root.addView(
            button(getString(R.string.action_refresh)) { refresh() }
        )
        root.addView(
            button(getString(R.string.action_clear)) { clearLog() }
        )

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
     */
    private fun refresh() {
        val lines = store.lineCount()
        val active = ModuleStatus.isModuleActive()
        statusView.text = buildString {
            append(getString(if (active) R.string.status_active else R.string.status_inactive))
            append("\n\n")
            append(getString(R.string.status_records, lines, store.sizeBytes() / 1024))
            if (active && lines == 0) {
                append("\n\n")
                append(getString(R.string.hint_no_records))
            }
        }
    }

    private fun shareLog() {
        val lines = store.lineCount()
        if (lines == 0) {
            toast(getString(R.string.toast_empty))
            return
        }
        val export = store.buildExport(header())
        if (export == null || !export.exists()) {
            toast(getString(R.string.toast_export_failed))
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", export)
        }.getOrNull()
        if (uri == null) {
            toast(getString(R.string.toast_export_failed))
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ProbeStore.EXPORT_NAME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Some targets (older chat apps) ignore EXTRA_STREAM flags unless the chooser
        // itself carries the grant, hence the flag on the chooser too.
        val chooser = Intent.createChooser(send, getString(R.string.action_share)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(chooser) }
            .onFailure { toast(getString(R.string.toast_no_share_target)) }
    }

    private fun clearLog() {
        store.clear()
        refresh()
        toast(getString(R.string.toast_cleared))
    }

    /**
     * Prepended to every export. Without the app/device/module versions, a log Jay
     * sends back cannot be tied to the build that produced it.
     */
    private fun header(): List<String> {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val hostVersion = runCatching {
            packageManager.getPackageInfo("com.twitter.android", 0).versionName
        }.getOrNull() ?: "not installed"
        return listOf(
            ProbeRecord.note(now, "export module=${BuildConfig.VERSION_NAME}"),
            ProbeRecord.note(now, "export host=com.twitter.android $hostVersion"),
            ProbeRecord.note(
                now,
                "export device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}",
            ),
            ProbeRecord.note(now, "export moduleActive=${ModuleStatus.isModuleActive()}"),
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
