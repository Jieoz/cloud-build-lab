package com.jieoz.rimetmock

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * The single config channel between the module app and the host (DingTalk).
 *
 * The app stores the whole [MockState] as one JSON string in an ordinary private preference and
 * mirrors it into libxposed remote preferences on every commit/apply. The host reads that copy
 * via [state]. There is no world-readable XML (the file LSPosed 2.2 warns about, 2.3 removes) and
 * no ContentProvider fallback — one key, one channel.
 */
object ModulePrefs {

    @Volatile
    private var framework: XposedInterface? = null

    fun bind(base: XposedInterface) {
        framework = base
    }

    private fun remote(): SharedPreferences? = framework?.getRemotePreferences(Constants.PREFS)

    /** Host-side read: the current state, or an empty default before anything is saved. */
    fun state(): MockState = MockState.fromJson(remote()?.getString(Constants.K_STATE, null))

    /** App-side handle that publishes to remote prefs on commit/apply. */
    fun open(context: Context): SharedPreferences =
        PublishingPrefs(
            context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        )

    /** App-side convenience: load + save the whole state. */
    fun load(context: Context): MockState =
        MockState.fromJson(
            context.applicationContext
                .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getString(Constants.K_STATE, null)
        )

    fun save(context: Context, state: MockState) {
        open(context).edit().putString(Constants.K_STATE, state.toJson()).apply()
    }

    private fun publish(local: SharedPreferences) {
        val remote = remote() ?: return
        remote.edit().putString(Constants.K_STATE, local.getString(Constants.K_STATE, null)).apply()
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
