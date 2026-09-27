package balti.xposed.pixelifygooglephotos.lsp102

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulePrefsPublishTest {

    @Test
    fun theUiWriterAndThePhotosReaderUseTheSameRemoteGroup() {
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
        assertEquals("prefs", Constants.SHARED_PREF_FILE_NAME)
    }

    @Test
    fun aFailedPublishIsNotReportedAsSuccess() {
        ModulePrefs.remoteWriter = { throw IllegalStateException("XposedService not bound") }
        var thrown = false
        try {
            ModulePrefs.publish(MemoryPrefs())
        } catch (t: IllegalStateException) {
            thrown = t.message == "XposedService not bound"
        }
        assertTrue(thrown)
        assertEquals("IllegalStateException: XposedService not bound", ModulePrefs.lastPublishError)
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
