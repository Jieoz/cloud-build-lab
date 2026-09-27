package balti.xposed.pixelifygooglephotos.lsp102

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * The module app writes remote preferences through XposedService.
 * The hooked Photos process reads them through XposedModule.
 * Those are different interfaces. Using the hook interface from the UI process
 * never publishes a value Photos can read.
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

    @Volatile
    private var service: XposedService? = null

    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
            }

            override fun onServiceDied(dead: XposedService) {
                if (service === dead) service = null
            }
        })
    }

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

    internal var remoteWriter: ((SharedPreferences) -> Unit)? = { local -> copyToRemote(local) }

    @Volatile
    var lastPublishError: String? = null
        private set

    fun publish(local: SharedPreferences) {
        try {
            remoteWriter?.invoke(local)
            lastPublishError = null
        } catch (t: Throwable) {
            lastPublishError = t.javaClass.simpleName + ": " + t.message
            throw t
        }
    }

    private fun copyToRemote(local: SharedPreferences) {
        val bound = service ?: throw IllegalStateException("XposedService not bound")
        val remote = bound.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
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
        if (!editor.commit()) throw IllegalStateException("remote commit returned false")
    }

    private class PublishingPrefs(private val local: SharedPreferences) : SharedPreferences by local {
        override fun edit(): SharedPreferences.Editor = PublishingEditor(local.edit(), local)
    }

    private class PublishingEditor(
        private val editor: SharedPreferences.Editor,
        private val local: SharedPreferences
    ) : SharedPreferences.Editor by editor {
        override fun apply() {
            editor.commit()
            publish(local)
        }

        override fun commit(): Boolean {
            val ok = editor.commit()
            if (ok) publish(local)
            return ok
        }
    }
}
