package com.jieoz.ctsshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.HookedTarget

/**
 * One switch + log shortcut, trimmed from rimet-mock's MainActivity. The status line reports what
 * LSPosed says about the Google :googleapp process (XposedService.getRunningTargets).
 */
class MainActivity : Activity() {

    private lateinit var pref: ModulePrefs.PublishingPrefs
    private lateinit var status: TextView
    private lateinit var publishStatus: TextView
    private lateinit var logSwitch: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pref = ModulePrefs.open(this)
        ModulePrefs.onServiceChanged = { runOnUiThread { refreshConnection() } }
        status = findViewById(R.id.status)
        publishStatus = findViewById(R.id.publish_status)
        logSwitch = findViewById(R.id.log_switch)
        findViewById<Button>(R.id.export_log).setOnClickListener { openDownloads() }
    }

    override fun onResume() {
        super.onResume()
        refreshConnection()
        logSwitch.setOnCheckedChangeListener(null)
        logSwitch.isChecked = pref.getBoolean(Constants.K_LOG, false)
        logSwitch.setOnCheckedChangeListener { _, checked -> persistLogSwitch(checked) }
    }

    override fun onDestroy() {
        ModulePrefs.onServiceChanged = null
        super.onDestroy()
    }

    private fun refreshConnection() {
        val targets = ModulePrefs.runningTargets()
        val hosts = targets.filterKeys { !it.startsWith(Constants.SELF_PACKAGE) }
        status.text = when {
            hosts.isNotEmpty() -> getString(
                R.string.status_hosts_hooked,
                hosts.entries.joinToString("、") { (name, t) -> "$name（${stateLabel(t)}）" }
            )
            ModulePrefs.isBound -> getString(R.string.status_no_host_running)
            else -> getString(R.string.status_service_down)
        }
        publishStatus.text = if (ModulePrefs.isBound) "" else getString(R.string.publish_waiting)
    }

    private fun stateLabel(t: HookedTarget): String = when (t.state) {
        HookedTarget.State.UP_TO_DATE -> getString(R.string.target_up_to_date)
        HookedTarget.State.STALE -> getString(R.string.target_stale)
        HookedTarget.State.RELOADING -> getString(R.string.target_reloading)
        HookedTarget.State.FAILED -> getString(R.string.target_failed)
    }

    private fun openDownloads() {
        Toast.makeText(this, R.string.debug_log_hint, Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
    }

    private fun persistLogSwitch(checked: Boolean) {
        val published = pref.edit().run {
            putBoolean(Constants.K_LOG, checked)
            commit()
        }
        if (published) {
            publishStatus.text = ""
            Toast.makeText(this, if (checked) R.string.log_on_toast else R.string.log_off_toast, Toast.LENGTH_SHORT).show()
        } else {
            publishStatus.text = getString(
                R.string.publish_failed,
                ModulePrefs.lastPublishError ?: getString(R.string.publish_failed_unknown)
            )
        }
    }
}
