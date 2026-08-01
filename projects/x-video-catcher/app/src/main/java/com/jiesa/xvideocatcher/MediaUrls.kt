package com.jiesa.xvideocatcher

/**
 * URL classification for X's media CDNs.
 *
 * Video lives on `video.twimg.com` as split audio/video HLS. Shapes confirmed from two
 * real captures (211 hits, 18 videos), all via OkHttp — this build of X has no Cronet:
 *
 *   .../<kind>/<id>/pl/<key>.m3u8                      master playlist
 *   .../<kind>/<id>/pl/avc1/1920x1080/<key>.m3u8       video variant playlist
 *   .../<kind>/<id>/pl/mp4a/128000/<key>.m3u8          audio variant playlist
 *   .../<kind>/<id>/vid/avc1/0/0/1920x1080/<k>.mp4     video init segment
 *   .../<kind>/<id>/vid/avc1/0/3000/1920x1080/<k>.m4s  video media segment
 *   .../<kind>/<id>/aud/mp4a/0/3000/32000/<k>.m4s      audio media segment
 *
 * Audio and video are separate tracks, so grabbing video segments alone yields a silent
 * file. A download has to take both.
 *
 * Photos live on a different host (`pbs.twimg.com`) with a different quality mechanism:
 * one stored image, resized on demand by a `name=` query parameter rather than by path.
 *
 *   https://pbs.twimg.com/media/<key>?format=jpg&name=small     ~680px
 *   https://pbs.twimg.com/media/<key>?format=jpg&name=orig      full stored size
 *   https://pbs.twimg.com/media/<key>.jpg                       defaults to medium
 *
 * That difference is why quality selection is split in two below: for video the answer
 * is the largest WxH in the ladder, for a photo it is the same URL with name=orig.
 */
object MediaUrls {

    /** `amplify_video` (promoted), `ext_tw_video` (user uploads), `tweet_video` (GIF). */
    private val MEDIA_KINDS = Regex("/(amplify_video|ext_tw_video|tweet_video)/")

    private val VIDEO_HOSTS = listOf(
        "video.twimg.com",
        "video-ft.twimg.com",
        "amp.twimg.com",
    )

    private val PHOTO_HOSTS = listOf(
        "pbs.twimg.com",
        "pbs-ft.twimg.com",
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

    /**
     * Photo paths worth keeping. `/media/` is a tweet photo; `/tweet_video_thumb/` and
     * `/ext_tw_video_thumb/` are video posters, kept because they identify a video post
     * even when its playlist was never requested.
     *
     * Deliberately excluded: `/profile_images/`, `/profile_banners/`, `/emoji/`,
     * `/card_img/`, `/semantic_core_img/`. A timeline scroll fetches hundreds of those
     * per minute and none of them is content the user asked to save — with them included
     * the log is unreadable, which is the same failure as the robots.txt noise.
     */
    private val PHOTO_PATH = Regex(
        """/(media|tweet_video_thumb|ext_tw_video_thumb|amplify_video_thumb)/""",
        RegexOption.IGNORE_CASE,
    )

    private val PHOTO_EXCLUDED = Regex(
        """/(profile_images|profile_banners|emoji|card_img|semantic_core_img|hashflag|ads-payload)/""",
        RegexOption.IGNORE_CASE,
    )

    fun isInteresting(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return isInterestingVideo(lower) || isInterestingPhoto(lower)
    }

    private fun isInterestingVideo(lower: String): Boolean {
        if (!MEDIA_PATH.containsMatchIn(lower) && !MEDIA_KINDS.containsMatchIn(lower)) return false
        // Either a known media host, or a known media path on some other CDN host —
        // X has changed CDN hostnames before, and the path shape is the stable part.
        return VIDEO_HOSTS.any { lower.contains(it) } || MEDIA_KINDS.containsMatchIn(lower)
    }

    private fun isInterestingPhoto(lower: String): Boolean {
        if (!PHOTO_HOSTS.any { lower.contains(it) }) return false
        if (PHOTO_EXCLUDED.containsMatchIn(lower)) return false
        return PHOTO_PATH.containsMatchIn(lower)
    }

    fun isPhoto(url: String): Boolean = isInterestingPhoto(url.lowercase())

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
     * Stable identity of a photo: the CDN key, which is what stays the same across the
     * small/medium/large/orig renderings of one image. Used to avoid saving the same
     * photo repeatedly as the timeline re-requests it at different sizes.
     */
    private val PHOTO_KEY = Regex(
        """/(?:media|tweet_video_thumb|ext_tw_video_thumb|amplify_video_thumb)/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun photoKey(url: String): String? =
        PHOTO_KEY.find(url)?.groupValues?.get(1)?.substringBefore('.')

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
     * Picks the highest-resolution video URL: the player requests several resolutions
     * while adapting to bandwidth, so "what was playing" is not "the best available".
     *
     * Ordered by pixel count rather than height, since X serves both portrait and
     * landscape (720x1280 and 1280x720 both appear in one capture, identical area) and
     * comparing height alone would rank them wrongly.
     */
    fun highestResolution(urls: Collection<String>): String? =
        urls.mapNotNull { u -> resolution(u)?.let { (w, h) -> u to w.toLong() * h } }
            .maxByOrNull { it.second }
            ?.first

    /**
     * Rewrites a photo URL to the full stored image.
     *
     * X keeps one image per photo and resizes on request, so quality is a query
     * parameter, not a separate URL: `name=small|medium|large|4096x4096|orig`. Any
     * existing `name` is replaced, and a missing one is added, so the result is the
     * largest rendering regardless of which size the timeline happened to load.
     *
     * The extension form (`/media/KEY.jpg`) is converted to the query form, because
     * `.jpg?name=orig` is honoured but leaves the format pinned; `?format=jpg&name=orig`
     * is the shape X's own clients use. Returns the input unchanged when it is not a
     * photo URL, so callers can apply it blindly.
     */
    fun highestQualityPhoto(url: String): String {
        if (!isPhoto(url)) return url

        val hashIndex = url.indexOf('#')
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""
        val withoutFragment = if (hashIndex >= 0) url.substring(0, hashIndex) else url

        val queryIndex = withoutFragment.indexOf('?')
        var path = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val query = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else ""

        val params = query.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }
            .toMutableList()

        // Move a path extension into format=, so one canonical shape comes out.
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        if (dot > slash) {
            val ext = path.substring(dot + 1).lowercase()
            if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) {
                path = path.substring(0, dot)
                if (params.none { it.first.equals("format", ignoreCase = true) }) {
                    params += "format" to if (ext == "jpeg") "jpg" else ext
                }
            }
        }
        if (params.none { it.first.equals("format", ignoreCase = true) }) {
            params += "format" to "jpg"
        }

        val rebuilt = params
            .filterNot { it.first.equals("name", ignoreCase = true) }
            .toMutableList()
        rebuilt += "name" to "orig"

        return path + "?" + rebuilt.joinToString("&") { "${it.first}=${it.second}" } + fragment
    }

    /** True for an audio-track URL: audio is a separate track and must be fetched too. */
    fun isAudioTrack(url: String): Boolean = url.contains("/aud/", ignoreCase = true)

    /** True for a video-track URL (not audio, not a playlist). */
    fun isVideoTrack(url: String): Boolean =
        url.contains("/vid/", ignoreCase = true) && !isManifest(url)
}
