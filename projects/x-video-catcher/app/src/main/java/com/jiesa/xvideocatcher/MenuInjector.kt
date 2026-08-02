package com.jiesa.xvideocatcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Adds a "下载" entry to the overflow ("more") menu on X's media viewer.
 *
 * ## Why the hook targets the platform, not X
 *
 * X 12.x is R8-obfuscated and pairip-wrapped, so there is no stable class or method name for
 * "the tweet detail screen" to hook — names change every release and pairip actively resists
 * inspection. The only names guaranteed to survive are the framework's own. Two platform
 * boundaries are used here, and both are load-bearing:
 *
 *  - `Activity.onCreateOptionsMenu` / `onPrepareOptionsMenu` — where an Android app populates
 *    its own overflow menu. Appending after X has populated it puts our item last, which is
 *    where a foreign action belongs; inserting first would displace X's own actions.
 *  - `Activity.onOptionsItemSelected` — where the tap arrives. We compare against our own
 *    generated item id and return `true` only for ours, so X's handling of every other item is
 *    untouched. Returning `true` indiscriminately would swallow X's menu taps, which is the
 *    kind of breakage that makes a module look like it broke the app.
 *
 * ## Why the menu item is conditional
 *
 * The item is only added when [MediaRegistry] holds something downloadable. An always-present
 * "下载" that reports "nothing to download" on a text-only tweet trains the user to distrust
 * it. On a media post the playlist has necessarily been fetched before the viewer can display
 * anything, so by the time the menu opens the registry is populated.
 *
 * ## Threading
 *
 * The tap handler must return immediately — downloading on the UI thread would freeze X and
 * trip its own ANR watchdog. Work is handed to a background thread and results come back to
 * the main looper as a Toast, because a foreign process is not somewhere to build UI.
 */
object MenuInjector {

    /** Distinct, stable ids for our own items so `onOptionsItemSelected` can recognise them. */
    private const val ITEM_VIDEO = 0x7C000001
    private const val ITEM_PHOTO = 0x7C000002

    private val main = Handler(Looper.getMainLooper())

    fun install(attached: MutableList<String>, skipped: MutableList<String>) {
        hookMenuCreation(attached, skipped)
        hookMenuSelection(attached, skipped)
    }

    private fun hookMenuCreation(
        attached: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        // Both are hooked: some screens build the menu once in onCreateOptionsMenu, others
        // rebuild it in onPrepareOptionsMenu as the viewed item changes. Adding in both, with
        // a findItem guard against duplicates, covers either style without assuming which
        // one X uses on a screen whose class name we cannot read.
        for (method in listOf("onCreateOptionsMenu", "onPrepareOptionsMenu")) {
            try {
                XposedHelpers.findAndHookMethod(
                    Activity::class.java,
                    method,
                    Menu::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val menu = param.args.getOrNull(0) as? Menu ?: return
                            val activity = param.thisObject as? Activity ?: return
                            runCatching { addItems(activity, menu) }
                        }
                    },
                )
                attached += "Activity.$method"
            } catch (t: Throwable) {
                skipped += "Activity.$method"
                ProbeLog.error("failed to hook $method", t)
            }
        }
    }

    private fun addItems(activity: Activity, menu: Menu) {
        if (!MediaRegistry.hasAnything()) return

        val video = MediaRegistry.latestVideo()
        if (video != null && menu.findItem(ITEM_VIDEO) == null) {
            menu.add(Menu.NONE, ITEM_VIDEO, Menu.CATEGORY_SECONDARY, "下载视频")
        }
        val photo = MediaRegistry.latestPhoto()
        if (photo != null && menu.findItem(ITEM_PHOTO) == null) {
            menu.add(Menu.NONE, ITEM_PHOTO, Menu.CATEGORY_SECONDARY, "下载图片")
        }
    }

    private fun hookMenuSelection(
        attached: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onOptionsItemSelected",
                MenuItem::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val item = param.args.getOrNull(0) as? MenuItem ?: return
                        val activity = param.thisObject as? Activity ?: return
                        when (item.itemId) {
                            ITEM_VIDEO -> {
                                startVideoDownload(activity)
                                // Consume only our own item; X's items fall through
                                // untouched, so its menu keeps working.
                                param.result = true
                            }
                            ITEM_PHOTO -> {
                                startPhotoDownload(activity)
                                param.result = true
                            }
                        }
                    }
                },
            )
            attached += "Activity.onOptionsItemSelected"
        } catch (t: Throwable) {
            skipped += "Activity.onOptionsItemSelected"
            ProbeLog.error("failed to hook onOptionsItemSelected", t)
        }
    }

    private fun startVideoDownload(context: Context) {
        val video = MediaRegistry.latestVideo()
        if (video == null) {
            toast(context, "还没有捕获到可下载的视频")
            return
        }
        toast(context, "开始下载视频…")
        Thread {
            val outcome = runCatching {
                DownloadJob.downloadVideo(context.applicationContext, video.mediaId, video.masterUrl)
            }.getOrElse { DownloadJob.Outcome.Failed("unexpected", it.javaClass.simpleName) }
            report(context, outcome)
        }.apply { isDaemon = true; name = "xvc-download" }.start()
    }

    private fun startPhotoDownload(context: Context) {
        val photo = MediaRegistry.latestPhoto()
        if (photo == null) {
            toast(context, "还没有捕获到可下载的图片")
            return
        }
        toast(context, "开始下载图片…")
        Thread {
            val outcome = runCatching {
                DownloadJob.downloadPhoto(context.applicationContext, photo)
            }.getOrElse { DownloadJob.Outcome.Failed("unexpected", it.javaClass.simpleName) }
            report(context, outcome)
        }.apply { isDaemon = true; name = "xvc-download" }.start()
    }

    /**
     * Reports an outcome, naming the failing stage.
     *
     * "下载失败" alone is unactionable — the stage distinguishes an expired playlist (video/audio)
     * from a storage problem (save) from a container problem (mux), which are three different
     * fixes.
     */
    private fun report(context: Context, outcome: DownloadJob.Outcome) {
        val message = when (outcome) {
            is DownloadJob.Outcome.Saved ->
                "已保存 ${outcome.fileName} (${outcome.bytes / 1024 / 1024}MB)" +
                    if (outcome.silent) " — 该视频无音轨" else ""
            is DownloadJob.Outcome.AlreadySaved -> "已存在:${outcome.fileName}"
            is DownloadJob.Outcome.NoRenditions -> "无法下载:${outcome.detail}"
            is DownloadJob.Outcome.Failed -> "下载失败(${outcome.stage}):${outcome.detail}"
        }
        ProbeLog.line("download outcome: $message")
        main.post { runCatching { toast(context, message) } }
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
