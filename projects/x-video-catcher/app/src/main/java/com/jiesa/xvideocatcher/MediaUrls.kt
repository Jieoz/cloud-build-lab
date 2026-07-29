package com.jiesa.xvideocatcher

/**
 * URL classification for the probe.
 *
 * X serves video from video.twimg.com as either an HLS manifest (.m3u8) or a
 * progressive MP4. Everything else (avatars, GraphQL, telemetry) is noise and must
 * be dropped inside the hook, because these hooks sit on the app's hot network path.
 */
object MediaUrls {

    private val MEDIA_HOST_HINTS = listOf(
        "video.twimg.com",
        "video-ft.twimg.com",
        "amp.twimg.com",
    )

    private val MEDIA_PATH_HINTS = listOf(
        ".m3u8",
        ".mp4",
        ".ts?",
        ".m4s",
        "/amplify_video/",
        "/ext_tw_video/",
        "/tweet_video/",
    )

    fun isInteresting(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        val hostMatch = MEDIA_HOST_HINTS.any { lower.contains(it) }
        val pathMatch = MEDIA_PATH_HINTS.any { lower.contains(it) } || lower.endsWith(".ts")
        // Host match alone is enough: it is already narrow and catches manifests
        // whose path extension is rewritten. Path match alone covers CDN changes.
        return hostMatch || pathMatch
    }

    /**
     * Manifests are the payload we actually want to surface later; segments are
     * high-volume and only useful as evidence that playback started.
     */
    fun isManifest(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains("/pl/")
    }
}
