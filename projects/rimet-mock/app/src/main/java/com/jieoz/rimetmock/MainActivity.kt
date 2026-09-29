package com.jieoz.rimetmock

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Profile list + master switch + diagnostic log switch. Each row shows a profile, lets you
 * activate it (radio), edit, or delete. Matches the original module's multi-profile model.
 * The editor lives in [EditActivity].
 *
 * Same prefs flow as pixelify-lsp102's ActivityMain: every write goes through
 * [ModulePrefs.open] (PublishingPrefs), so commit() publishes to the remote store and throws
 * on failure; the switch handlers show that error and revert.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var state: MockState
    private lateinit var pref: SharedPreferences

    private lateinit var status: TextView
    private lateinit var publishStatus: TextView
    private lateinit var masterSwitch: CheckBox
    private lateinit var logSwitch: CheckBox
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pref = ModulePrefs.open(this)
        status = findViewById(R.id.status)
        publishStatus = findViewById(R.id.publish_status)
        masterSwitch = findViewById(R.id.master_switch)
        logSwitch = findViewById(R.id.log_switch)
        list = findViewById(R.id.profile_list)

        masterSwitch.setOnCheckedChangeListener { _, checked ->
            state = state.copy(enabled = checked)
            persist()
        }
        logSwitch.setOnCheckedChangeListener { _, checked -> persistLogSwitch(checked) }
        findViewById<Button>(R.id.add).setOnClickListener {
            startActivity(EditActivity.intent(this, null))
        }
        findViewById<Button>(R.id.export_log).setOnClickListener { shareDebugLog() }
    }

    override fun onResume() {
        super.onResume()
        state = MockState.fromJson(pref.getString(Constants.K_STATE, null))
        render()
    }

    private fun render() {
        status.text = if (ModuleUtils.isActive()) getString(R.string.status_active)
        else getString(R.string.status_inactive)

        masterSwitch.setOnCheckedChangeListener(null)
        masterSwitch.isChecked = state.enabled
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            state = state.copy(enabled = checked); persist()
        }

        logSwitch.setOnCheckedChangeListener(null)
        logSwitch.isChecked = pref.getBoolean(Constants.K_LOG, false)
        logSwitch.setOnCheckedChangeListener { _, checked -> persistLogSwitch(checked) }

        list.removeAllViews()
        if (state.profiles.isEmpty()) {
            val tv = TextView(this)
            tv.text = getString(R.string.no_profiles)
            tv.setPadding(0, 24, 0, 0)
            list.addView(tv)
            return
        }
        for (p in state.profiles) list.addView(row(p))
    }

    /** pixelify-style: open the Downloads app where DebugLog wrote its file. */
    private fun shareDebugLog() {
        Toast.makeText(this, R.string.debug_log_hint, Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
        }
    }

    private fun persistLogSwitch(checked: Boolean) {
        try {
            pref.edit().run {
                putBoolean(Constants.K_LOG, checked)
                commit()
            }
            Toast.makeText(
                this,
                if (checked) R.string.log_on_toast else R.string.log_off_toast,
                Toast.LENGTH_LONG
            ).show()
        } catch (failure: Throwable) {
            logSwitch.isChecked = !checked
            showPublishError(failure)
        }
    }

    private fun row(p: Profile): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, 12, 0, 12)

        val radio = RadioButton(this)
        radio.isChecked = p.id == state.activeId
        radio.setOnClickListener {
            state = state.copy(activeId = p.id); persist(); render()
        }
        row.addView(radio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val label = TextView(this)
        val sub = buildString {
            append(String.format("%.6f, %.6f", p.latitude, p.longitude))
            if (p.address.isNotEmpty()) append("\n${p.address}")
            val extras = mutableListOf<String>()
            if (p.maskEnv) extras.add("WiFi/基站:${p.scanResults.size}/${p.cellInfos.size}")
            if (p.jitter) extras.add("抖动")
            if (extras.isNotEmpty()) append("\n${extras.joinToString(" · ")}")
        }
        label.text = "${p.name}\n$sub"
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val edit = Button(this)
        edit.text = getString(R.string.edit)
        edit.setOnClickListener { startActivity(EditActivity.intent(this, p.id)) }
        row.addView(edit)

        val del = Button(this)
        del.text = getString(R.string.delete)
        del.setOnClickListener {
            state = state.copy(
                profiles = state.profiles.filterNot { it.id == p.id },
                activeId = if (state.activeId == p.id) null else state.activeId
            )
            persist(); render()
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
        }
        row.addView(del)
        return row
    }

    private fun persist() {
        try {
            val ok = pref.edit().run {
                putString(Constants.K_STATE, state.toJson())
                commit()
            }
            if (!ok) throw IllegalStateException("commit returned false")
            publishStatus.text = ""
        } catch (failure: Throwable) {
            showPublishError(failure)
        }
    }

    private fun showPublishError(failure: Throwable) {
        publishStatus.text = getString(
            R.string.publish_failed,
            ModulePrefs.lastPublishError ?: failure.javaClass.simpleName
        )
    }
}
