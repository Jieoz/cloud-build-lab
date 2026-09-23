package com.jieoz.rimetmock

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Settings written by the module app and read inside the host (DingTalk).
 *
 * The app stores an ordinary private preference, then mirrors it into libxposed remote
 * preferences. The host reads that copy. There is no world-readable XML — the file LSPosed 2.2
 * warns about and 2.3 removes. This replaces the original module's XSharedPreferences +
 * ContentProvider fallback chain with the single libxposed remote-prefs channel.
 */
object ModulePrefs {

    private val KEYS = arrayOf(
        Constants.K_ENABLED,
        Constants.K_LAT,
        Constants.K_LNG,
        Constants.K_ACCURACY,
        Constants.K_ALTITUDE,
        Constants.K_MASK_ENV,
        Constants.K_VERBOSE,
    )

    @Volatile
    private var framework: XposedInterface? = null

    fun bind(base: XposedInterface) {
        framework = base
    }

    /** Host-side read handle. Null before the module is bound (i.e. outside a hooked process). */
    fun remote(): SharedPreferences? = framework?.getRemotePreferences(Constants.PREFS)

    /** App-side handle: a normal private prefs that publishes to remote prefs on commit/apply. */
    fun open(context: Context): SharedPreferences {
        val local = context.applicationContext.getSharedPreferences(
            Constants.PREFS, Context.MODE_PRIVATE
        )
        return PublishingPrefs(local)
    }

    private fun publish(local: SharedPreferences) {
        val remote = remote() ?: return
        val editor = remote.edit()
        editor.clear()
        for (key in KEYS) {
            if (!local.contains(key)) continue
            when (val value = local.all[key]) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.apply()
    }

    private class PublishingPrefs(private val local: SharedPreferences) :
        SharedPreferences by local {
        override fun edit(): SharedPreferences.Editor = PublishingEditor(local.edit(), local)
    }

    private class PublishingEditor(
        private val editor: SharedPreferences.Editor,
        private val local: SharedPreferences
    ) : SharedPreferences.Editor by editor {
        override fun apply() {
            editor.apply()
            publish(local)
        }

        override fun commit(): Boolean {
            val ok = editor.commit()
            if (ok) publish(local)
            return ok
        }
    }
}
