package com.jiesa.xvideocatcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * The bridge between the two processes involved.
 *
 * The hooks run inside X (a different UID), so they cannot write into this module's
 * private storage directly. They insert records here instead, and this provider — which
 * runs in the module's own process — appends them to the file the UI can later export.
 *
 * The provider must be exported for X's process to reach it, which means every other
 * app on the device can also see it. Access is therefore checked per call against
 * [ProbeContract.ALLOWED_WRITERS] rather than relying on the manifest alone.
 */
open class ProbeProvider : ContentProvider() {

    private lateinit var store: ProbeStore

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        store = ProbeStore(ctx)
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        requireAllowedCaller()
        val payload = values?.getAsString(ProbeContract.COLUMN_LINES) ?: return null
        val lines = payload.split('\n').filter { it.isNotBlank() }
        val written = store.append(lines)
        return if (written > 0) ProbeContract.LOG_URI else null
    }

    /**
     * Reports current log stats. Used by the module UI, and useful from `adb shell
     * content query` as a quick "is the probe actually seeing anything" check.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireAllowedCaller()
        val cursor = MatrixCursor(arrayOf("lines", "bytes"))
        cursor.addRow(arrayOf(store.lineCount(), store.sizeBytes()))
        return cursor
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        requireAllowedCaller()
        return if (store.clear()) 1 else 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String = "application/x-ndjson"

    /**
     * Resolves the calling package for the *current* binder call. Overridable so the
     * access check can be tested directly; there is no binder identity in a unit test.
     */
    internal open fun resolveCallingPackage(): String? = callingPackage

    /**
     * Throws rather than returning quietly so a rejected write is visible in logcat
     * instead of looking like a probe that saw nothing.
     */
    private fun requireAllowedCaller() {
        val caller = resolveCallingPackage()
        if (caller != null && caller in ProbeContract.ALLOWED_WRITERS) return
        throw SecurityException("caller not allowed: $caller")
    }
}
