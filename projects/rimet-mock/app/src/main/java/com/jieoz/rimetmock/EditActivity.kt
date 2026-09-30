package com.jieoz.rimetmock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

/**
 * Create / edit a single profile. Coordinates + all address fields are editable (address text is
 * manual instead of auto-geocoded — the only feature that needed the AMap SDK). "读取当前环境"
 * captures the real GPS fix and a consistent WiFi/cell snapshot in one shot.
 */
class EditActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "profile_id"
        fun intent(ctx: Context, id: String?): Intent =
            Intent(ctx, EditActivity::class.java).apply { if (id != null) putExtra(EXTRA_ID, id) }
        private const val REQ_LOC = 1001
    }

    private lateinit var state: MockState
    private var editing: Profile? = null

    // captured framework snapshot carried until save
    private var capturedWifiEnabled = true
    private var capturedScans: List<String> = emptyList()
    private var capturedConn: String? = null
    private var capturedCells: List<String> = emptyList()
    private var capturedOperator = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        state = MockState.fromJson(
            ModulePrefs.open(this).getString(Constants.K_STATE, null)
        )
        editing = intent.getStringExtra(EXTRA_ID)?.let { id -> state.profiles.firstOrNull { it.id == id } }

        editing?.let { p ->
            capturedWifiEnabled = p.wifiEnabled
            capturedScans = p.scanResults
            capturedConn = p.connectionInfo
            capturedCells = p.cellInfos
            capturedOperator = p.operator
            bind(p)
        }
        updateEnvLabel()

        findViewById<Button>(R.id.capture).setOnClickListener { capture() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    private fun f(id: Int) = findViewById<EditText>(id)

    private fun bind(p: Profile) {
        f(R.id.name).setText(p.name)
        f(R.id.lat).setText(p.latitude.toString())
        f(R.id.lng).setText(p.longitude.toString())
        f(R.id.accuracy).setText(p.accuracy.toString())
        f(R.id.altitude).setText(p.altitude.toString())
        f(R.id.address).setText(p.address)
        f(R.id.province).setText(p.province)
        f(R.id.city).setText(p.city)
        f(R.id.district).setText(p.district)
        f(R.id.street).setText(p.street)
        f(R.id.poiName).setText(p.poiName)
        f(R.id.adCode).setText(p.adCode)
        findViewById<CheckBox>(R.id.jitter).isChecked = p.jitter
        findViewById<CheckBox>(R.id.mask_env).isChecked = p.maskEnv
    }

    private fun capture() {
        if (!EnvCapture.hasLocationPermission(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC
            )
            return
        }
        val loc = EnvCapture.lastLocation(this)
        if (loc != null) {
            f(R.id.lat).setText(loc.latitude.toString())
            f(R.id.lng).setText(loc.longitude.toString())
            if (loc.hasAccuracy()) f(R.id.accuracy).setText(loc.accuracy.toString())
            if (loc.hasAltitude()) f(R.id.altitude).setText(loc.altitude.toString())
        }
        val snap = EnvCapture.captureEnv(this, blankProfile())
        capturedWifiEnabled = snap.wifiEnabled
        capturedScans = snap.scanResults
        capturedConn = snap.connectionInfo
        capturedCells = snap.cellInfos
        capturedOperator = snap.operator
        updateEnvLabel()
        Toast.makeText(this, R.string.captured, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) capture()
    }

    private fun updateEnvLabel() {
        findViewById<TextView>(R.id.env_label).text =
            getString(R.string.env_captured, capturedScans.size, capturedCells.size)
    }

    private fun blankProfile() = Profile(id = "", name = "", latitude = 0.0, longitude = 0.0)

    private fun save() {
        val rawLat = f(R.id.lat).text.toString().trim().toDoubleOrNull()
        val rawLng = f(R.id.lng).text.toString().trim().toDoubleOrNull()
        if (rawLat == null || rawLng == null) {
            Toast.makeText(this, R.string.err_coords, Toast.LENGTH_SHORT).show(); return
        }
        // A latitude outside ±90 is only possible as micro-degrees (AMap E6 convention, e.g.
        // 40859545 == 40.859545). Field evidence: a profile saved with lat=4.0859545E7 sailed
        // through and produced a fix DingTalk can never use. Heal E6 automatically; validate hard.
        var lat = rawLat
        var lng = rawLng
        if (kotlin.math.abs(lat) > 90.0) lat /= 1_000_000.0
        if (kotlin.math.abs(lng) > 180.0) lng /= 1_000_000.0
        if (kotlin.math.abs(lat) > 90.0 || kotlin.math.abs(lng) > 180.0) {
            Toast.makeText(this, R.string.err_coords, Toast.LENGTH_SHORT).show(); return
        }
        // Write the healed values back so what the user sees == what is stored.
        f(R.id.lat).setText(lat.toString())
        f(R.id.lng).setText(lng.toString())
        val name = f(R.id.name).text.toString().trim().ifEmpty {
            String.format("%.5f, %.5f", lat, lng)
        }
        val id = editing?.id ?: UUID.randomUUID().toString()
        val p = Profile(
            id = id, name = name,
            latitude = lat, longitude = lng,
            accuracy = f(R.id.accuracy).text.toString().toFloatOrNull() ?: 5f,
            altitude = f(R.id.altitude).text.toString().toDoubleOrNull() ?: 0.0,
            address = f(R.id.address).text.toString().trim(),
            province = f(R.id.province).text.toString().trim(),
            city = f(R.id.city).text.toString().trim(),
            district = f(R.id.district).text.toString().trim(),
            street = f(R.id.street).text.toString().trim(),
            poiName = f(R.id.poiName).text.toString().trim(),
            adCode = f(R.id.adCode).text.toString().trim(),
            jitter = findViewById<CheckBox>(R.id.jitter).isChecked,
            maskEnv = findViewById<CheckBox>(R.id.mask_env).isChecked,
            wifiEnabled = capturedWifiEnabled,
            scanResults = capturedScans,
            connectionInfo = capturedConn,
            cellInfos = capturedCells,
            operator = capturedOperator,
        )
        val others = state.profiles.filterNot { it.id == id }
        val newState = state.copy(
            profiles = others + p,
            activeId = state.activeId ?: id  // first profile becomes active by default
        )
        // The local write is staged even when the LSPosed binder has not arrived yet; the
        // bind listener republishes it. Stay on this screen only when the remote publish
        // itself failed for a real reason, so a saved profile is never silently dropped.
        val published = ModulePrefs.open(this).edit().run {
            putString(Constants.K_STATE, newState.toJson())
            commit()
        }
        if (published) {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        } else if (ModulePrefs.isBound) {
            Toast.makeText(
                this,
                getString(
                    R.string.publish_failed,
                    ModulePrefs.lastPublishError ?: getString(R.string.publish_failed_unknown)
                ),
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, R.string.saved_pending, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
