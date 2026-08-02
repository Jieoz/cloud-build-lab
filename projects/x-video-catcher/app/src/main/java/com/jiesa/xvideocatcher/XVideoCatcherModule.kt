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

        // First: nothing can be written to disk until a Context exists.
        bindHostContext(attached, skipped)
        hookUrlConstruction(attached, skipped)
        hookMediaPlayer(attached, skipped)
        hookCronet(param.classLoader, attached, skipped)
        hookOkHttp(param.classLoader, attached, skipped)

        // The download entry point. Installed after the network layers so that by the time a
        // menu can be opened, the layers feeding MediaRegistry are already live.
        MenuInjector.install(attached, skipped)

        ProbeLog.line("probe layers active=$attached inactive=$skipped")
    }

    /**
     * Hands the host Context to [ProbeLog] as soon as one exists.
     *
     * This is not optional plumbing: `handleLoadPackage` runs *before* the host's
     * Application is created, so at attach time there is no Context and nothing can be
     * written. Hooking `Application.onCreate` is the first moment a usable Context
     * exists, and binding it there is what makes the log file appear without waiting
     * for a video to play.
     */
    private fun bindHostContext(
        attached: MutableList<String>,
        skipped: MutableList<String>,
    ) = layer("Application.onCreate", attached, skipped) {
        XposedHelpers.findAndHookMethod(
            android.app.Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(hookParam: MethodHookParam) {
                    val app = hookParam.thisObject as? android.content.Context ?: return
                    ProbeLog.bindContext(app)
                }
            },
        )
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
     * Absent from X 12.11.1: a real capture showed ClassNotFoundException for
     * org.chromium.net.CronetEngine, and all 86 media hits arrived via OkHttp. Kept
     * anyway, because X has shipped Cronet before and a silent switch back would
     * otherwise take the probe blind. Its absence is logged as an expected condition
     * rather than an error, so it does not look like a fault every launch.
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
     * Filters noise before doing any expensive work. These callbacks run on the app's
     * network hot path, so the cheap string check comes first and the stack trace is
     * only materialised for URLs that look like media.
     *
     * The label distinguishes a master playlist from variants and segments, because
     * the master is the only URL a download needs — reading that straight out of the
     * log is what makes the next capture answer "what shape do user uploads and GIFs
     * use", without re-deriving it from a wall of segment URLs.
     */
    private fun report(source: String, url: String, force: Boolean = false) {
        if (!force && !MediaUrls.isInteresting(url)) return
        // Audio is tested before the generic playlist case: an audio playlist
        // (/pl/mp4a/128000/…) is also a playlist, so checking `isManifest` first labelled
        // all 26 captured audio playlists "variant" and hid the audio ladder entirely.
        val kind = when {
            MediaUrls.isPhoto(url) -> "photo"
            MediaUrls.isMasterPlaylist(url) -> "master"
            MediaUrls.isAudioTrack(url) -> if (MediaUrls.isManifest(url)) "audio-playlist" else "audio"
            MediaUrls.isManifest(url) -> "variant"
            else -> "segment"
        }

        // Feed the download button. Only masters and photos are remembered: a master is the
        // only video URL a download can work from (variant and segment keys are random and
        // unrelated, so nothing can be derived from them), and a photo is a single request.
        // Recording variants here would let the menu offer a download it cannot complete.
        when (kind) {
            "master" -> MediaUrls.mediaId(url)?.let { MediaRegistry.rememberMaster(it, url) }
            "photo" -> MediaRegistry.rememberPhoto(url)
        }

        ProbeLog.candidate("$source/$kind", url, Throwable().stackTrace)
    }

    /**
     * Attaches one layer, keeping a failure local to it.
     *
     * A missing class is an ordinary fact about this build of X, not a fault, so it is
     * recorded in the summary line only. Anything else means the hook itself is broken
     * and gets a full stack trace — mixing the two made every launch look like it had
     * an error in it, which is how a real failure gets overlooked.
     */
    private inline fun layer(
        name: String,
        attached: MutableList<String>,
        skipped: MutableList<String>,
        block: () -> Unit,
    ) {
        try {
            block()
            attached += name
        } catch (absent: ClassNotFoundException) {
            skipped += name
        } catch (absent: NoSuchMethodException) {
            skipped += name
        } catch (t: Throwable) {
            skipped += name
            ProbeLog.error("layer '$name' failed to attach", t)
        }
    }
}
