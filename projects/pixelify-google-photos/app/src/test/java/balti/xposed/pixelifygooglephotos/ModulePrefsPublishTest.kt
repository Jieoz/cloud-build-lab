package balti.xposed.pixelifygooglephotos

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the settings write path.
 *
 * The settings process is outside this module's hook scope, so the hook interface is null there.
 * The old implementation returned silently in that situation: the switch looked toggled but
 * Google Photos never saw the value. publish() must fail loudly instead.
 */
class ModulePrefsPublishTest {

    @Test
    fun withoutAServiceThePublishFailsLoudly() {
        // Exercise the real service write path: no XposedService is bound in a JVM test,
        // so it must throw instead of silently pretending the setting reached Photos.
        ModulePrefs.remoteWriter = { local -> ModulePrefs.publishToService(local) }
        var message: String? = null
        try {
            ModulePrefs.publish(MemoryPrefs())
            message = "no exception"
        } catch (t: IllegalStateException) {
            message = t.message
        }
        assertEquals("XposedService not bound", message)
        assertEquals("IllegalStateException: XposedService not bound", ModulePrefs.lastPublishError)
    }

    @Test
    fun aSuccessfulWriterReachesTheRemoteGroup() {
        val remote = MemoryPrefs()
        ModulePrefs.remoteWriter = { local ->
            val editor = remote.edit()
            editor.clear()
            if (local.contains(Constants.PREF_ENABLE_VERBOSE_LOGS)) {
                editor.putBoolean(
                    Constants.PREF_ENABLE_VERBOSE_LOGS,
                    local.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false)
                )
            }
            editor.commit()
        }
        val local = MemoryPrefs()
        local.edit().putBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, true).commit()
        ModulePrefs.publish(local)
        assertTrue(remote.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
        assertEquals(null, ModulePrefs.lastPublishError)
        assertEquals("prefs", Constants.SHARED_PREF_FILE_NAME)
    }

    private class MemoryPrefs : SharedPreferences {
        private val values = linkedMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, def: String?) = values[key] as? String ?: def
        override fun getStringSet(key: String, def: MutableSet<String>?) = def
        override fun getInt(key: String, def: Int) = values[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = values[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = values[key] as? Float ?: def
        override fun getBoolean(key: String, def: Boolean) = values[key] as? Boolean ?: def
        override fun contains(key: String) = key in values
        override fun edit() = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String) = put(key, null)
            override fun clear(): SharedPreferences.Editor { values.keys.forEach { pending[it] = null }; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v } }
            private fun put(key: String, value: Any?): SharedPreferences.Editor { pending[key] = value; return this }
        }
    }
}
