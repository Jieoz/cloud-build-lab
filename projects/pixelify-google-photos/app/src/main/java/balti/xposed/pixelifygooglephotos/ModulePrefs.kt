package balti.xposed.pixelifygooglephotos

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Settings written by the module app and read inside Google Photos.
 *
 * The app stores an ordinary private preference, then copies it into libxposed remote
 * preferences. The host reads that copy. There is no world-readable XML, which is the file
 * LSPosed 2.2 warns about and 2.3 removes.
 */
object ModulePrefs {

    private val KEYS = arrayOf(
        Constants.PREF_SPOOF_FEATURES_LIST,
        Constants.PREF_DEVICE_TO_SPOOF,
        Constants.PREF_STRICTLY_CHECK_GOOGLE_PHOTOS,
        Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
        Constants.PREF_ENABLE_VERBOSE_LOGS,
        Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
        Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
        Constants.PREF_LAST_VERSION
    )

    @Volatile
    private var framework: XposedInterface? = null

    fun bind(base: XposedInterface) {
        framework = base
    }

    fun open(context: Context): SharedPreferences {
        val local = context.applicationContext.getSharedPreferences(
            Constants.SHARED_PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        return PublishingPrefs(local)
    }

    fun remote(): SharedPreferences? {
        val base = framework ?: return null
        return base.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
    }

    fun publish(local: SharedPreferences) {
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
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        editor.commit()
    }

    private class PublishingPrefs(private val local: SharedPreferences) : SharedPreferences by local {
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
