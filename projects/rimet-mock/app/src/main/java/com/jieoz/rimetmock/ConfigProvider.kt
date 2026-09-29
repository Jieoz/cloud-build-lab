package com.jieoz.rimetmock

import android.content.ContentProvider
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

/**
 * Binder-free config channel, served from this app's local prefs.
 *
 * LSPosed pushes the XposedService binder on uid-state transitions, and on some devices /
 * LSPosed builds that push never arrives — leaving the app "not bound" forever and the host
 * reading nothing. This provider removes that dependency: DingTalk reads the config directly
 * (content://com.jieoz.rimetmock.config/...) exactly like the original module's
 * ProfileProvider channel, which provably worked on LSPosed 2.3. Reads are live against the
 * local prefs file, so a save here is visible to the host with no publish step at all.
 *
 * Exported, but gated: only this app, DingTalk, the system uid and root may read.
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = Constants.SELF_PACKAGE + ".config"
        val URI_STATE: Uri = Uri.parse("content://$AUTHORITY/state")
        val URI_LOG: Uri = Uri.parse("content://$AUTHORITY/log_enabled")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (!callerAllowed()) return null
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        return when (uri.path) {
            "/state" -> single(Constants.K_STATE, prefs.getString(Constants.K_STATE, null))
            "/log_enabled" -> single(
                Constants.K_LOG,
                if (prefs.getBoolean(Constants.K_LOG, false)) "1" else "0"
            )
            else -> null
        }
    }

    /** Only self, the host, LSPosed's daemon uid and root may read the spoof config. */
    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) return true
        val names = try {
            context!!.packageManager.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        } ?: return false
        return names.any { it == Constants.HOST_DINGTALK || it == Constants.SELF_PACKAGE }
    }

    private fun single(column: String, value: String?): Cursor =
        MatrixCursor(arrayOf(column), 1).apply { addRow(arrayOf(value)) }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
