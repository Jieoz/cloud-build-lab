package com.jiesa.xvideocatcher

/**
 * Parser for the HLS playlists X serves on `video.twimg.com`.
 *
 * This exists because the probe's captured URL set is **not** a usable download source,
 * which the earlier design assumed it was. Measured on Jay's three captures (488 distinct
 * URLs, 33 videos):
 *
 *  - The master playlist's key is unrelated to the keys of its own variants and segments
 *    (26 masters checked, 0 overlap). So a master URL cannot be reconstructed from a
 *    variant, and a variant cannot be reconstructed from a segment (43 pairs, 0 overlap).
 *    Anything that "derives" one from another is guessing at a random 16-char token.
 *  - What the player fetched is not what is available. On 8 of 10 videos whose master was
 *    captured, X had only ever requested the 32000 audio track while the master offers
 *    64000 and 128000 — a downloader that picks the best *captured* audio ships the worst
 *    available one. Video is the same story: two videos advertise 1080x1920 in the master
 *    and no 1080 URL was ever captured.
 *
 * The master playlist is therefore the only authority on what exists, and it has to be
 * fetched and parsed rather than pattern-matched. Selection happens on its contents, not
 * on the capture.
 *
 * All parsing is deliberately tolerant: an unknown tag is skipped rather than failing the
 * playlist, since a download must not break because X added an attribute.
 */
object HlsPlaylist {

    /** One video rendition advertised by a master playlist. */
    data class VideoVariant(
        val url: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long,
        val codecs: String,
        /** GROUP-ID of the audio rendition this variant is meant to play with, if any. */
        val audioGroup: String?,
    ) {
        val pixels: Long get() = width.toLong() * height
    }

    /** One audio rendition advertised by a master playlist. */
    data class AudioTrack(
        val url: String,
        val groupId: String,
        val name: String?,
    ) {
        /**
         * X encodes the bitrate in both the group id (`audio-128000`) and the URL path
         * (`/pl/mp4a/128000/`). The path is the more reliable of the two: the group id is
         * a label X chooses, the path segment is what the CDN routes on. Falls back to the
         * group id, then to 0 so an unparseable track sorts last rather than crashing.
         */
        val bitrate: Int
            get() = PATH_BITRATE.find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: GROUP_BITRATE.find(groupId)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
    }

    data class Master(
        val url: String,
        val videoVariants: List<VideoVariant>,
        val audioTracks: List<AudioTrack>,
    ) {
        /**
         * Best video by pixel count, not height: X serves both orientations and one
         * capture contained 720x1280 and 1280x720 together (identical area), so comparing
         * height alone ranks them wrongly. Bandwidth breaks a pixel tie — same frame size
         * at a higher bitrate is the better encode.
         */
        val bestVideo: VideoVariant?
            get() = videoVariants.maxWithOrNull(
                compareBy<VideoVariant> { it.pixels }.thenBy { it.bandwidth }
            )

        /**
         * Best audio by bitrate, ignoring which group the chosen video variant points at.
         *
         * X pairs its 320x568 variant with `audio-32000`, so honouring the pairing would
         * cap audio at whatever the lowest-bandwidth ladder rung uses. The renditions are
         * interchangeable — same content, same duration, different bitrate — and the
         * capture shows X itself switching groups as it adapts.
         */
        val bestAudio: AudioTrack?
            get() = audioTracks.maxByOrNull { it.bitrate }
    }

    /** A variant playlist: the init segment plus the media segments, in order. */
    data class Media(
        val url: String,
        val initSegment: String?,
        val segments: List<String>,
    )

    private val PATH_BITRATE = Regex("""/pl/mp4a/(\d+)/""", RegexOption.IGNORE_CASE)
    private val GROUP_BITRATE = Regex("""(\d+)""")
    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val BANDWIDTH = Regex("""[^A-Z-]BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)

    fun isMasterBody(body: String): Boolean = body.contains("#EXT-X-STREAM-INF")

    /**
     * Parses a master playlist. Returns null when the body is not one — a variant
     * playlist fetched by mistake must not silently produce an empty master, which would
     * look like "this video has no renditions" instead of "wrong URL".
     */
    fun parseMaster(url: String, body: String): Master? {
        if (!isMasterBody(body)) return null

        val audio = mutableListOf<AudioTrack>()
        val videos = mutableListOf<VideoVariant>()
        val lines = body.lines()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    if (attrs["TYPE"].equals("AUDIO", ignoreCase = true)) {
                        val uri = attrs["URI"]
                        val group = attrs["GROUP-ID"]
                        if (!uri.isNullOrEmpty() && !group.isNullOrEmpty()) {
                            audio += AudioTrack(resolve(url, uri), group, attrs["NAME"])
                        }
                    }
                }

                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    // The URI is on the next non-blank, non-comment line. X emits a blank
                    // line between the EXT-X-MEDIA block and the first stream, so the scan
                    // cannot just take i+1.
                    val uriLine = nextUri(lines, i + 1)
                    if (uriLine != null) {
                        val spec = line.substringAfter(':')
                        val res = RESOLUTION.find(spec)
                        val attrs = parseAttributes(spec)
                        videos += VideoVariant(
                            url = resolve(url, uriLine),
                            width = res?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                            height = res?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                            bandwidth = BANDWIDTH.find(" $spec")?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                            codecs = attrs["CODECS"] ?: "",
                            audioGroup = attrs["AUDIO"],
                        )
                    }
                }
            }
            i++
        }
        return Master(url, videos, audio)
    }

    /**
     * Parses a variant (media) playlist into its init segment and media segments.
     *
     * Order is preserved and segments are not deduplicated: a fetch has to write them in
     * playlist order, and the byte stream is only valid if the init segment comes first.
     */
    fun parseMedia(url: String, body: String): Media {
        var init: String? = null
        val segments = mutableListOf<String>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                parseAttributes(line.substringAfter(':'))["URI"]?.let { init = resolve(url, it) }
                continue
            }
            if (line.startsWith("#")) continue
            segments += resolve(url, line)
        }
        return Media(url, init, segments)
    }

    private fun nextUri(lines: List<String>, from: Int): String? {
        var i = from
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isNotEmpty() && !line.startsWith("#")) return line
            i++
        }
        return null
    }

    /**
     * Resolves a playlist URI against the playlist's own URL.
     *
     * X uses host-absolute paths (`/amplify_video/…`), but relative ones are legal HLS and
     * a CDN change could start emitting them, so both are handled.
     */
    internal fun resolve(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return uri
        val hostStart = schemeEnd + 3
        val hostEnd = base.indexOf('/', hostStart)
        val origin = if (hostEnd < 0) base else base.substring(0, hostEnd)
        if (uri.startsWith("/")) return origin + uri
        val path = if (hostEnd < 0) "/" else base.substring(hostEnd)
        val dir = path.substringBeforeLast('/', "")
        return "$origin$dir/$uri"
    }

    /**
     * Splits an HLS attribute list, honouring quoted values.
     *
     * A naive `split(",")` breaks on `CODECS="mp4a.40.2,avc1.4D401E"` — the comma inside
     * the quotes ends the CODECS value early and turns the rest into a bogus attribute,
     * which is how AUDIO group ids get lost on exactly the variants that have them.
     */
    internal fun parseAttributes(spec: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val current = StringBuilder()
        var quoted = false
        val parts = mutableListOf<String>()
        for (c in spec) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    current.append(c)
                }
                c == ',' && !quoted -> {
                    parts += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()

        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().uppercase()
            var value = part.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            out[key] = value
        }
        return out
    }
}
