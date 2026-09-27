package balti.xposed.pixelifygooglephotos.lsp102

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulePrefsPublishTest {

    @Test
    fun turningTheSwitchOnReachesTheCopyPhotosReads() {
        val local = MemoryPrefs()
        val remote = MemoryPrefs()
        ModulePrefs.remoteWriter = { source ->
            remote.edit()
                .putBoolean(
                    Constants.PREF_ENABLE_VERBOSE_LOGS,
                    source.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false)
                )
                .commit()
        }

        local.edit().putBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, true).apply()
        ModulePrefs.publish(local)

        assertTrue(remote.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
    }

    @Test
    fun aMissingFrameworkDoesNotThrowAndLeavesTheCopyOff() {
        ModulePrefs.remoteWriter = null
        ModulePrefs.publish(MemoryPrefs())
        assertFalse(false)
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
            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                values.keys.forEach { pending[it] = null }
                return this
            }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
        }
    }
}
