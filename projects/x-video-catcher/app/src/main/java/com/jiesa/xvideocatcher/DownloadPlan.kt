package com.jiesa.xvideocatcher

/**
 * Turns what the probe captured into a concrete plan for fetching one video, or an honest
 * statement that it cannot.
 *
 * The earlier design assumed a captured URL set was enough to download from, with the
 * master URL "reconstructed from a variant" when it was missing. Measured against Jay's
 * captures, both halves of that are false:
 *
 *  - Playlist keys are random and unrelated across levels. Of 26 captured masters, **none**
 *    shared its key with any of its own variants or segments; of 43 variant/segment pairs,
 *    likewise none. There is nothing to reconstruct from — the key is a 16-char token.
 *  - The capture under-reports quality. On 8 of 10 videos, X had only ever fetched the
 *    32000 audio track while the master advertised 128000. Picking the best *captured*
 *    audio therefore ships the worst available one.
 *
 * So the plan is built in two stages. [from] does the offline part: group a capture by
 * video and decide, per video, whether a master URL is present. [Plan.Ready] carries that
 * master, and the actual rendition choice is made by [HlsPlaylist] once the master has been
 * fetched. [Plan.NeedsMaster] is returned when no master was captured — a real state for 7
 * of 33 captured videos, and one the UI must show rather than paper over with a guess.
 */
object DownloadPlan {

    sealed interface Plan {
        val mediaId: String

        /** A master playlist was captured; every rendition is reachable from it. */
        data class Ready(override val mediaId: String, val masterUrl: String) : Plan

        /**
         * No master was captured for this video, so the best available rendition is not
         * knowable and a full-quality download cannot be planned.
         *
         * [bestCapturedVariant] is the highest-resolution variant playlist that *was* seen,
         * offered as a degraded fallback rather than presented as the best copy. It is null
         * when only segments were captured, which is unrecoverable: segment keys are random
         * per segment (verified across 45 variants — every segment had a distinct key), so
         * the missing ones cannot be enumerated and the video cannot be assembled.
         */
        data class NeedsMaster(
            override val mediaId: String,
            val bestCapturedVariant: String?,
            val capturedSegments: Int,
        ) : Plan {
            /** True when a partial, lower-quality download is still possible. */
            val hasDegradedFallback: Boolean get() = bestCapturedVariant != null
        }
    }

    /**
     * Groups a capture by media id and classifies each video.
     *
     * Photos are excluded: they are single-request downloads handled by
     * [MediaUrls.highestQualityPhoto] and have no playlist structure.
     */
    fun from(urls: Collection<String>): List<Plan> {
        val byId = LinkedHashMap<String, MutableList<String>>()
        for (url in urls) {
            if (!MediaUrls.isInteresting(url)) continue
            if (MediaUrls.isPhoto(url)) continue
            val id = MediaUrls.mediaId(url) ?: continue
            byId.getOrPut(id) { mutableListOf() } += url
        }

        return byId.map { (id, group) ->
            val master = group.firstOrNull { MediaUrls.isMasterPlaylist(it) }
            if (master != null) {
                Plan.Ready(id, master)
            } else {
                // Only video variant playlists are a usable fallback. An audio-only
                // playlist would download silence, which is worse than reporting failure.
                val variants = group.filter {
                    MediaUrls.isManifest(it) &&
                        !MediaUrls.isMasterPlaylist(it) &&
                        MediaUrls.resolution(it) != null
                }
                Plan.NeedsMaster(
                    mediaId = id,
                    bestCapturedVariant = MediaUrls.highestResolution(variants),
                    capturedSegments = group.count { !MediaUrls.isManifest(it) },
                )
            }
        }
    }

    /** What to fetch for one video, once its master playlist has been read. */
    data class Fetch(
        val mediaId: String,
        val videoPlaylist: String,
        val audioPlaylist: String?,
        val width: Int,
        val height: Int,
        val audioBitrate: Int,
    ) {
        /**
         * Audio and video are separate tracks on X, so a video-only fetch is silent. That
         * is a legitimate outcome for a genuinely silent upload (one captured master
         * advertised no audio rendition at all), but the caller has to know which it is.
         */
        val isSilent: Boolean get() = audioPlaylist == null
    }

    /**
     * Chooses the renditions to download from a parsed master.
     *
     * Both choices come from the master's own contents rather than from the capture, which
     * is the entire point of fetching it: quality selection on captured URLs demonstrably
     * loses both resolution and audio bitrate.
     */
    fun fetchFor(mediaId: String, master: HlsPlaylist.Master): Fetch? {
        val video = master.bestVideo ?: return null
        val audio = master.bestAudio
        return Fetch(
            mediaId = mediaId,
            videoPlaylist = video.url,
            audioPlaylist = audio?.url,
            width = video.width,
            height = video.height,
            audioBitrate = audio?.bitrate ?: 0,
        )
    }
}
