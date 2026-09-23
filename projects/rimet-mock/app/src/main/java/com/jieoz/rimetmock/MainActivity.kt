package com.jieoz.rimetmock

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal control UI. Writes target coordinates + toggles into the module's private prefs, which
 * PublishingPrefs mirrors into libxposed remote prefs for the host to read.
 *
 * No account, no network, no map SDK. "Use current GPS" reads framework LocationManager only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = ModulePrefs.open(this)

        val status = findViewById<TextView>(R.id.status)
        val enabled = findViewById<CheckBox>(R.id.enabled)
        val maskEnv = findViewById<CheckBox>(R.id.mask_env)
        val lat = findViewById<EditText>(R.id.lat)
        val lng = findViewById<EditText>(R.id.lng)
        val accuracy = findViewById<EditText>(R.id.accuracy)

        enabled.isChecked = prefs.getBoolean(Constants.K_ENABLED, false)
        maskEnv.isChecked = prefs.getBoolean(Constants.K_MASK_ENV, false)
        lat.setText(prefs.getString(Constants.K_LAT, ""))
        lng.setText(prefs.getString(Constants.K_LNG, ""))
        accuracy.setText(prefs.getString(Constants.K_ACCURACY, "5"))

        // isActive() is hooked to return true when the module is loaded — mirrors the original's
        // "module not enabled?" self-check.
        status.text = if (ModuleUtils.isActive()) {
            getString(R.string.status_active)
        } else {
            getString(R.string.status_inactive)
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val latV = lat.text.toString().trim().toDoubleOrNull()
            val lngV = lng.text.toString().trim().toDoubleOrNull()
            if (enabled.isChecked && (latV == null || lngV == null)) {
                Toast.makeText(this, R.string.err_coords, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putBoolean(Constants.K_ENABLED, enabled.isChecked)
                .putBoolean(Constants.K_MASK_ENV, maskEnv.isChecked)
                .putString(Constants.K_LAT, lat.text.toString().trim())
                .putString(Constants.K_LNG, lng.text.toString().trim())
                .putString(Constants.K_ACCURACY, accuracy.text.toString().trim())
                .apply()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.use_current).setOnClickListener {
            val loc = CurrentLocation.read(this)
            if (loc == null) {
                Toast.makeText(this, R.string.err_no_fix, Toast.LENGTH_SHORT).show()
            } else {
                lat.setText(loc.latitude.toString())
                lng.setText(loc.longitude.toString())
            }
        }
    }
}
