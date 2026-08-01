package com.jiesa.xvideocatcher

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Stage 1 of the X video download module: a read-only probe.
 *
 * It does not modify anything in the host app. Its only job is to answer the
 * question a downloader needs answered first — *where* does X 12.11.1 hand a
 * playable media URL to its player, on this device (Android 14 / API 34)?
 *
 * X is R8-obfuscated and (since the 10.x line) wrapped in pairip protection, so
 * hooking app classes by name is not viable. Every hook here therefore targets a
 * *stable, non-obfuscatable* boundary: platform classes and third-party network
 * entry points that keep their names. Each layer is attached independently and
 * failures are logged rather than propagated, so one missing class cannot take the
 * whole probe (or X) down.
 */
class XVideoCatcherModule : IXposedHookLoadPackage {

    private val targets = setOf("com.twitter.android", "com.twitter.android.beta")

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == BuildConfig.APPLICATION_ID) {
            markSelfActive(param.classLoader)
            return
        }
        if (param.packageName !in targets) return
        if (!param.isFirstApplication) return

        ProbeLog.line("attached to ${param.packageName} (process=${param.processName})")

        val attached = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        hookUrlConstruction(attached, skipped)
        hookMediaPlayer(attached, skipped)
        hookCronet(param.classLoader, attached, skipped)
        hookOkHttp(param.classLoader, attached, skipped)

        ProbeLog.line("probe layers active=$attached inactive=$skipped")
        // Write immediately rather than waiting for the first media URL, so the log
        // file proves attachment on its own.
        ProbeLog.flushNow()
    }

    /**
     * Flips [ModuleStatus.isModuleActive] inside our own UI process, so the status the
     * screen reports comes from the framework actually having loaded us rather than
     * from a stored flag that could go stale.
     */
    private fun markSelfActive(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                ModuleStatus::class.java.name,
                classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true),
            )
        }
    }

    /**
     * Layer A — java.net.URL construction.
     *
     * Broadest net: OkHttp, HttpURLConnection and most Java-side HTTP stacks build a
     * URL or parse one on the way out. Catches the URL string plus the call stack,
     * which is what identifies the obfuscated caller class.
     */
    private fun hookUrlConstruction(
        attached: MutableList<String>,
        skipped: MutableList<String>,
    ) = layer("java.net.URL", attached, skipped) {
        XposedHelpers.findAndHookConstructor(
            java.net.URL::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(hookParam: MethodHookParam) {
                    val spec = hookParam.args.getOrNull(0) as? String ?: return
                    report("java.net.URL(String)", spec)
                }
            },
        )
    }

    /**
     * Layer B — android.media.MediaPlayer.
     *
     * Only hit if X falls back to the platform player (GIF-style looping video and
     * some progressive MP4 paths). Cheap to keep and unambiguous when it fires.
     */
    private fun hookMediaPlayer(
        attached: MutableList<String>,
        skipped: MutableList<String>,
    ) = layer("MediaPlayer.setDataSource", attached, skipped) {
        XposedHelpers.findAndHookMethod(
            android.media.MediaPlayer::class.java,
            "setDataSource",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(hookParam: MethodHookParam) {
                    val spec = hookParam.args.getOrNull(0) as? String ?: return
                    report("MediaPlayer.setDataSource", spec, force = true)
                }
            },
        )
    }

    /**
     * Layer C — Cronet.
     *
     * X ships Cronet, and org.chromium.net.* survives obfuscation because it is
     * loaded reflectively and paired with native code. The public entry point that
     * always sees the URL is CronetEngine.newUrlRequestBuilder(String, ...).
     */
    private fun hookCronet(
        classLoader: ClassLoader,
        attached: MutableList<String>,
        skipped: MutableList<String>,
    ) = layer("CronetEngine.newUrlRequestBuilder", attached, skipped) {
        val engine = XposedHelpers.findClassIfExists("org.chromium.net.CronetEngine", classLoader)
            ?: throw ClassNotFoundException("org.chromium.net.CronetEngine")
        var hooks = 0
        engine.declaredMethods
            .filter { it.name == "newUrlRequestBuilder" }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(hookParam: MethodHookParam) {
                        val spec = hookParam.args.getOrNull(0) as? String ?: return
                        report("Cronet.newUrlRequestBuilder", spec)
                    }
                })
                hooks++
            }
        if (hooks == 0) throw NoSuchMethodException("newUrlRequestBuilder")
    }

    /**
     * Layer D — OkHttp, only when class names survived.
     *
     * Expected to be inactive on a release build of X; kept because when it *is*
     * active it gives the cleanest request-level view, and its absence is itself
     * useful information about how the app was built.
     */
    private fun hookOkHttp(
        classLoader: ClassLoader,
        attached: MutableList<String>,
        skipped: MutableList<String>,
    ) = layer("okhttp3.Request\$Builder.url", attached, skipped) {
        val builder = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
            ?: throw ClassNotFoundException("okhttp3.Request\$Builder")
        XposedHelpers.findAndHookMethod(
            builder,
            "url",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(hookParam: MethodHookParam) {
                    val spec = hookParam.args.getOrNull(0) as? String ?: return
                    report("okhttp3.Request.Builder.url", spec)
                }
            },
        )
    }

    /**
     * Filters noise before doing any expensive work. These callbacks run on the
     * app's network hot path, so the cheap string check comes first and the stack
     * trace is only materialised for URLs that look like media.
     */
    private fun report(source: String, url: String, force: Boolean = false) {
        if (!force && !MediaUrls.isInteresting(url)) return
        val kind = if (MediaUrls.isManifest(url)) "manifest" else "media"
        ProbeLog.candidate("$source/$kind", url, Throwable().stackTrace)
    }

    private inline fun layer(
        name: String,
        attached: MutableList<String>,
        skipped: MutableList<String>,
        block: () -> Unit,
    ) {
        try {
            block()
            attached += name
        } catch (t: Throwable) {
            skipped += name
            ProbeLog.error("layer '$name' not attached", t)
        }
    }
}
