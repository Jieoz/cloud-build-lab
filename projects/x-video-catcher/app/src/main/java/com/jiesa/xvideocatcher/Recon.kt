package com.jiesa.xvideocatcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Reconnaissance hooks. These answer questions the download design depends on and that
 * cannot be settled from the container: they observe what X actually does on a real device
 * rather than what its code is assumed to do.
 *
 * Four open questions, each mapped to one recorder below:
 *
 *  1. **Is there a tweet id in this process?** The share-intent design fetches renditions
 *     from the syndication endpoint, which is keyed by *tweet* id. Every URL captured so
 *     far carries only a *media* id, and the two are not interchangeable — feeding a media
 *     id to that endpoint returns 404 (verified). If no tweet id is observable, the share
 *     design cannot resolve what to download and the whole approach is dead.
 *  2. **Which class builds the three-dot sheet?** The previous attempt hooked
 *     `Activity.onCreateOptionsMenu`. All three menu hooks attached, yet no item appeared,
 *     which means X's sheet is not a platform options menu. Naming the real builder is the
 *     prerequisite for injecting into it.
 *  3. **What does X put on the clipboard / into a share Intent?** "Copy link" and "Share"
 *     already exist in that sheet and already know the tweet URL. Whatever they pass is a
 *     ready-made answer to question 1, obtained without defeating obfuscation.
 *  4. **Are the sheet's labels readable?** If the entries are `TextView`s with real text,
 *     an injection can find its anchor by label instead of by obfuscated class name — the
 *     one identifier X's own translators keep stable.
 *
 * Everything here is read-only: it records and returns. No behaviour of the host is altered,
 * so a wrong guess in this file cannot break X.
 */
object Recon {

    /** Bounds the volume: a timeline scroll would otherwise flood the log with duplicates. */
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun once(key: String, body: () -> Unit) {
        if (seen.size > 400) return
        if (seen.add(key)) body()
    }

    fun install(attached: MutableList<String>, skipped: MutableList<String>) {
        hookClipboard(attached, skipped)
        hookShareIntents(attached, skipped)
        hookSheetCreation(attached, skipped)
        hookMenuInflation(attached, skipped)
    }

    /**
     * Question 3a — the clipboard.
     *
     * "Copy link" writes the canonical tweet URL, which contains the tweet id in plain text.
     * `ClipboardManager.setPrimaryClip` is a framework method, so this hook cannot be broken
     * by obfuscation, and the value it carries is exactly what the share design needs.
     */
    private fun hookClipboard(attached: MutableList<String>, skipped: MutableList<String>) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.content.ClipboardManager",
                null,
                "setPrimaryClip",
                "android.content.ClipData",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clip = param.args.getOrNull(0) ?: return
                        val text = runCatching {
                            val n = XposedHelpers.callMethod(clip, "getItemCount") as Int
                            (0 until n).joinToString(" | ") { i ->
                                val item = XposedHelpers.callMethod(clip, "getItemAt", i)
                                XposedHelpers.callMethod(item, "getText")?.toString() ?: ""
                            }
                        }.getOrNull() ?: return
                        if (text.isBlank()) return
                        ProbeLog.line("RECON clipboard: $text")
                        recordTweetId("clipboard", text)
                    }
                },
            )
            attached += "RECON ClipboardManager.setPrimaryClip"
        } catch (t: Throwable) {
            skipped += "RECON ClipboardManager.setPrimaryClip"
            ProbeLog.error("recon: clipboard hook failed", t)
        }
    }

    /**
     * Question 3b — share Intents.
     *
     * The existing "Share" entry builds an ACTION_SEND carrying the tweet URL. Hooking
     * `Activity.startActivity` catches it at the framework boundary, whichever obfuscated
     * class assembled it. This also reveals whether the URL is present *before* the system
     * chooser opens, which is what a download-from-share design would consume.
     */
    private fun hookShareIntents(attached: MutableList<String>, skipped: MutableList<String>) {
        for (method in listOf("startActivity", "startActivityForResult")) {
            try {
                XposedHelpers.findAndHookMethod(
                    Activity::class.java,
                    method,
                    Intent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val intent = param.args.getOrNull(0) as? Intent ?: return
                            describeIntent(intent, param.thisObject?.javaClass?.name)
                        }
                    },
                )
                attached += "RECON Activity.$method"
            } catch (t: Throwable) {
                // Overloads differ by SDK; a miss here is not fatal because the other
                // recorders cover the same question.
                skipped += "RECON Activity.$method"
            }
        }
    }

    private fun describeIntent(intent: Intent, from: String?) {
        val action = intent.action ?: return
        // Only sharing and viewing matter; the app fires many unrelated intents.
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_CHOOSER &&
            action != Intent.ACTION_VIEW
        ) return

        val parts = mutableListOf("action=$action", "from=$from")
        intent.type?.let { parts += "type=$it" }
        intent.dataString?.let { parts += "data=$it" }
        runCatching {
            intent.extras?.keySet()?.forEach { key ->
                val v = intent.extras?.get(key)
                val text = when (v) {
                    is CharSequence -> v.toString()
                    is Intent -> "Intent(${v.action} ${v.type} ${v.getStringExtra(Intent.EXTRA_TEXT)})"
                    else -> v?.javaClass?.simpleName ?: "null"
                }
                if (text.length in 1..400) parts += "$key=$text"
            }
        }
        val line = parts.joinToString("  ")
        once("intent:${line.take(200)}") {
            ProbeLog.line("RECON intent: $line")
            recordTweetId("intent", line)
        }
    }

    /**
     * Questions 2 and 4 — the sheet itself.
     *
     * `Activity.onCreateOptionsMenu` produced nothing, so the sheet is a view hierarchy, not
     * a platform menu. Hooking `Activity.onAttachedToWindow` is too early to see it; instead
     * this records the labels of any view tree that gets attached after a long press or a
     * three-dot tap, by walking down from the activity's content root when a dialog-ish
     * window appears. `DialogFragment.onStart` and `Dialog.show` are the two framework
     * boundaries a bottom sheet almost always passes through, whatever it is called.
     */
    private fun hookSheetCreation(attached: MutableList<String>, skipped: MutableList<String>) {
        for (target in listOf("android.app.Dialog", "androidx.fragment.app.DialogFragment")) {
            for (method in listOf("show", "onStart")) {
                try {
                    XposedHelpers.findAndHookMethod(
                        target,
                        null,
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val owner = param.thisObject?.javaClass?.name ?: return
                                val root = runCatching {
                                    val dialog = if (target == "android.app.Dialog") param.thisObject
                                    else XposedHelpers.callMethod(param.thisObject, "getDialog")
                                    val window = XposedHelpers.callMethod(dialog, "getWindow")
                                    XposedHelpers.callMethod(window, "getDecorView") as View
                                }.getOrNull()
                                val labels = root?.let { collectLabels(it) } ?: emptyList()
                                once("sheet:$owner:${labels.take(4)}") {
                                    ProbeLog.line(
                                        "RECON sheet: owner=$owner method=$method labels=$labels"
                                    )
                                }
                            }
                        },
                    )
                    attached += "RECON $target.$method"
                } catch (t: Throwable) {
                    skipped += "RECON $target.$method"
                }
            }
        }
    }

    /**
     * Also record classic menu inflation, in case *some* screens do use a platform menu.
     * The previous build hooked the Activity callbacks and saw nothing; `MenuInflater.inflate`
     * catches the case where X inflates a menu into a toolbar it owns instead.
     */
    private fun hookMenuInflation(attached: MutableList<String>, skipped: MutableList<String>) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.MenuInflater",
                null,
                "inflate",
                Int::class.javaPrimitiveType,
                Menu::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val menu = param.args.getOrNull(1) as? Menu ?: return
                        val items = (0 until menu.size()).mapNotNull { i ->
                            menu.getItem(i)?.title?.toString()
                        }
                        once("inflate:$items") {
                            ProbeLog.line("RECON menu inflated: id=${param.args[0]} items=$items")
                        }
                    }
                },
            )
            attached += "RECON MenuInflater.inflate"
        } catch (t: Throwable) {
            skipped += "RECON MenuInflater.inflate"
        }
    }

    /** Reads visible labels out of a view tree; bounded so a deep hierarchy cannot stall X. */
    private fun collectLabels(root: View, limit: Int = 25): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View, depth: Int) {
            if (out.size >= limit || depth > 12) return
            if (v is TextView) {
                val t = v.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length < 60) out += t
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i) ?: continue, depth + 1)
                }
            }
        }
        runCatching { walk(root, 0) }
        return out
    }

    /**
     * Question 1 — pull a tweet id out of any text that contains a status URL.
     *
     * Recorded separately and explicitly, because this single fact decides whether the
     * share-based design is viable. A bare "found a URL" line would leave it to be
     * eyeballed later.
     */
    private fun recordTweetId(source: String, text: String) {
        TweetUrl.allIdsIn(text).forEach { id ->
            once("tweetid:$id") {
                ProbeLog.line("RECON TWEET_ID source=$source id=$id")
            }
        }
    }
}
