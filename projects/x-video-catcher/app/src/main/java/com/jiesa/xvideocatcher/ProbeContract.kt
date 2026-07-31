package com.jiesa.xvideocatcher

import android.net.Uri

/**
 * Single source of truth shared by the hook side (running inside X's process) and the
 * provider side (running in this module's process). Both ends must agree on the
 * authority, the column name, and who is allowed to write — keeping that agreement in
 * one file is what stops the two halves from drifting apart.
 */
object ProbeContract {

    const val AUTHORITY = "com.jiesa.xvideocatcher.probe"

    /** Column carrying a newline-joined batch of already-formatted JSONL records. */
    const val COLUMN_LINES = "lines"

    val LOG_URI: Uri = Uri.parse("content://$AUTHORITY/log")

    /**
     * Only the hooked app and the module itself may write. The provider has to be
     * exported so X's process can reach it, so this allowlist is the actual access
     * control — without it any installed app could inject records or spam the log.
     */
    val ALLOWED_WRITERS = setOf(
        "com.twitter.android",
        "com.twitter.android.beta",
        "com.jiesa.xvideocatcher",
    )
}
