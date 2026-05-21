package com.jiesa.motochargeboost

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast

class ChargeBoostTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        Toast.makeText(this, getString(R.string.toast_tile_opening), Toast.LENGTH_SHORT).show()

        val intent = Intent().apply {
            setComponent(TargetActivities.chargeBoost)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapse(intent)
            }
        }.onFailure {
            applicationContext.launchTarget(TargetActivities.chargeBoost)
        }
    }
}
