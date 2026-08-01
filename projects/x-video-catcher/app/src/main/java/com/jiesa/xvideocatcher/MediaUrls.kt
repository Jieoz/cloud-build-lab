package com.jiesa.xvideocatcher

/**
 * URL classification for X's media CDN.
 *
 * Shapes confirmed from a real probe capture (86 hits, 7 videos), all of which came
 * through OkHttp — this build of X has no Cronet at all:
 *
 *   .../<kind>/<id>/pl/<key>.m3u8                    master playlist
 *   .../<kind>/<id>/pl/avc1/1920x1080/<key>.m3u8     video variant playlist
 *   .../<kind>/<id>/pl/mp4a/128000/<key>.m3u8        audio variant playlist
 *   .../<kind>/<id>/vid/avc1/0/0/1920x1080/<k>.mp4   video init segment
 *   .../<kind>/<id>/vid/avc1/0/3000/1920x1080/<k>.m4s  video media segment
 *   .../<kind>/<id>/aud/mp4a/0/3000/128000/<k>.m4s   audio media segment
 *
 * Audio and video are separate tracks, so grabbing video segments alone yields a
 * silent file. A download has to take both.
 */
object MediaUrls {

    /** `amplify_video` (promoted), `ext_tw_video` (user uploads), `tweet_video` (GIF). */
    private val MEDIA_KINDS = Regex("/(amplify_video|ext_tw_video|tweet_video)/")

    private val MEDIA_HOSTS = listOf(
        "video.twimg.com",
        "video-ft.twimg.com",
        "amp.twimg.com",
    )

    /**
     * A media-looking path. Required even on the video host: matching the host alone
     * logged `https://video.twimg.com/robots.txt` on every launch, which is exactly
     * the kind of noise that buries the URL actually being played.
     */
    private val MEDIA_PATH = Regex(
        """(\.m3u8|\.mp4|\.m4s|\.ts)(\?|$)|/pl/|/vid/|/aud/|/seg/""",
        RegexOption.IGNORE_CASE,
    )

    fun isInteresting(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (!MEDIA_PATH.containsMatchIn(lower) && !MEDIA_KINDS.containsMatchIn(lower)) return false
        // Either a known media host, or a known media path on some other CDN host —
        // X has changed CDN hostnames before, and the path shape is the stable part.
        return MEDIA_HOSTS.any { lower.contains(it) } || MEDIA_KINDS.containsMatchIn(lower)
    }

    /** Any playlist. */
    fun isManifest(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains("/pl/")
    }

    /**
     * The master playlist: `/pl/<key>.m3u8` with no codec/resolution segment in
     * between. This is the only URL a downloader needs — every variant, resolution and
     * the audio track are reachable from it, so it is what gets surfaced to the user.
     *
     * Variant playlists (`/pl/avc1/1920x1080/…`) are deliberately excluded: they carry
     * one resolution only, and picking from them would mean trusting whichever quality
     * the player happened to switch to rather than the best available.
     */
    private val MASTER_PLAYLIST = Regex(
        """/(?:amplify_video|ext_tw_video|tweet_video)/\d+/pl/[A-Za-z0-9_-]+\.m3u8""",
        RegexOption.IGNORE_CASE,
    )

    fun isMasterPlaylist(url: String): Boolean = MASTER_PLAYLIST.containsMatchIn(url)

    /**
     * Tweet id from a media URL, used to group the segments of one video together.
     * Note this is the *media* id, not the status id from the tweet's web link.
     */
    private val MEDIA_ID = Regex("""/(?:amplify_video|ext_tw_video|tweet_video)/(\d+)/""")

    fun mediaId(url: String): String? = MEDIA_ID.find(url)?.groupValues?.get(1)

    /**
     * Resolution encoded in a variant or segment URL, as width x height.
     * Returns null for audio and for master playlists, which carry no resolution.
     */
    private val RESOLUTION = Regex("""/(\d{2,5})x(\d{2,5})/""")

    fun resolution(url: String): Pair<Int, Int>? {
        val m = RESOLUTION.find(url) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        return w to h
    }

    /**
     * Picks the highest-resolution URL, which is what Jay wants downloaded: the player
     * requests several resolutions while adapting to bandwidth, so "what was playing"
     * is not the same as "the best available".
     *
     * Ordered by pixel count rather than height, since X serves both portrait and
     * landscape (720x1280 and 1280x720 both appear in one capture) and comparing
     * height alone would rank them wrongly.
     */
    fun highestResolution(urls: Collection<String>): String? =
        urls.mapNotNull { u -> resolution(u)?.let { (w, h) -> u to w.toLong() * h } }
            .maxByOrNull { it.second }
            ?.first

    /** True for an audio-track URL: audio is a separate track and must be fetched too. */
    fun isAudioTrack(url: String): Boolean = url.contains("/aud/", ignoreCase = true)

    /** True for a video-track URL (not audio, not a playlist). */
    fun isVideoTrack(url: String): Boolean =
        url.contains("/vid/", ignoreCase = true) && !isManifest(url)
}
