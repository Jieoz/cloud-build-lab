package com.jiesa.motochargeboost

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

fun Context.launchTarget(component: android.content.ComponentName): Boolean {
    return try {
        val intent = Intent().apply {
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.toast_no_target), Toast.LENGTH_SHORT).show()
        false
    } catch (_: Exception) {
        Toast.makeText(this, getString(R.string.toast_no_target), Toast.LENGTH_SHORT).show()
        false
    }
}
