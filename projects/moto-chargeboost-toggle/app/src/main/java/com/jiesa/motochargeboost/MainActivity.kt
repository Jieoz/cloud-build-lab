package com.jiesa.motochargeboost

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jiesa.motochargeboost.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_OPEN_CHARGEBOOST) {
            launchTarget(TargetActivities.chargeBoost)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvCapabilityState.text = getString(R.string.status_checking)

        binding.btnOpenChargeBoost.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_opening), Toast.LENGTH_SHORT).show()
            if (!launchTarget(TargetActivities.chargeBoost)) {
                Toast.makeText(this, getString(R.string.toast_no_target), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnToggleAssist.setOnClickListener {
            val capability = CapabilityDetector.detect(this)
            if (!capability.accessibilityEnabled) {
                Toast.makeText(this, getString(R.string.hint_not_enabled), Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            if (!capability.hasDirectActivity) {
                Toast.makeText(this, getString(R.string.toast_no_target), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AssistState.pendingToggle = true
            Toast.makeText(this, getString(R.string.toast_assist_start), Toast.LENGTH_SHORT).show()
            launchTarget(TargetActivities.chargeBoost)
        }
    }

    override fun onResume() {
        super.onResume()
        renderCapabilityState(CapabilityDetector.detect(this))
    }

    private fun renderCapabilityState(capability: CapabilityState) {
        binding.tvCapabilityState.text = when {
            capability.canAssistToggle() -> getString(R.string.status_ready_assist)
            capability.hasDirectActivity -> getString(R.string.status_ready_direct)
            else -> getString(R.string.status_unavailable)
        }

        binding.tvAccessibilityState.text = if (capability.accessibilityEnabled) {
            getString(R.string.hint_enabled)
        } else {
            getString(R.string.hint_not_enabled)
        }

        binding.btnOpenChargeBoost.isEnabled = capability.hasDirectActivity
        binding.btnToggleAssist.isEnabled = capability.hasDirectActivity
    }

    companion object {
        const val ACTION_OPEN_CHARGEBOOST = "com.jiesa.motochargeboost.action.OPEN_CHARGEBOOST"
    }
}
