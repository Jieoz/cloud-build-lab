package com.jiesa.xvideocatcher

import java.io.File
import java.io.OutputStream

/**
 * Fetches one HLS track (video or audio) into a single playable file.
 *
 * X serves **fragmented MP4**, not MPEG-TS — confirmed by reading the actual bytes of a
 * captured init segment and media segment:
 *
 *   init  (`/vid/avc1/0/0/1080x1920/<k>.mp4`) → `ftyp` `free` `moov`
 *   media (`/vid/avc1/0/3000/1080x1920/<k>.m4s`) → `styp` `moof` `mdat`
 *
 * That shape is why concatenation works here at all, and also why it only works in one exact
 * order. A fMP4 track is playable as `init + segment + segment + …`: the `moov` carries the
 * track definition and each `moof` carries its own timing. Dropping the init segment (the
 * `#EXT-X-MAP` URI) produces a file with sample data and no track description — every player
 * rejects it, and the failure looks like a corrupt download rather than a missing box.
 *
 * The reverse mistake matters just as much: this is *not* how you combine audio with video.
 * Concatenating an audio track onto a video track yields one file containing two unrelated
 * movie headers, and a player reads the first and ignores the rest — which is exactly what a
 * silent "successful" download looks like. Audio and video are downloaded separately here and
 * combined by [Muxer], which remaps them into one container.
 */
object TrackDownloader {

    /** Progress callback: (completed segments, total segments). */
    fun interface Progress {
        fun onSegment(done: Int, total: Int)
    }

    class Failure(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Downloads the track described by [playlistUrl] into [into].
     *
     * Returns the number of bytes written. Throws [Failure] if the playlist has no segments,
     * which is a real condition worth surfacing: it means the URL was a master (already
     * handled upstream) or an expired playlist, and writing a 0-byte file that the gallery
     * shows as a broken video is the outcome to avoid.
     */
    fun download(playlistUrl: String, into: OutputStream, progress: Progress? = null): Long {
        val body = try {
            Http.text(playlistUrl)
        } catch (t: Throwable) {
            throw Failure("could not fetch playlist", t)
        }

        // A master here would parse as a media playlist with zero segments, so name the
        // real problem instead of reporting "empty".
        if (HlsPlaylist.isMasterBody(body)) {
            throw Failure("expected a variant playlist but got a master")
        }

        val media = HlsPlaylist.parseMedia(playlistUrl, body)
        if (media.segments.isEmpty()) {
            throw Failure("playlist advertised no segments")
        }

        var total = 0L
        // Init segment first and unconditionally: without its `moov` the concatenated
        // segments have no track description and the file is unplayable.
        media.initSegment?.let { init ->
            total += try {
                Http.copyTo(init, into)
            } catch (t: Throwable) {
                throw Failure("init segment failed", t)
            }
        }

        media.segments.forEachIndexed { index, segment ->
            total += try {
                Http.copyTo(segment, into)
            } catch (t: Throwable) {
                // Name the segment that failed. A partial video is not recoverable by
                // retrying the whole download, and "segment 41/210 failed" is what makes an
                // expired-playlist diagnosis possible.
                throw Failure("segment ${index + 1}/${media.segments.size} failed", t)
            }
            progress?.onSegment(index + 1, media.segments.size)
        }
        return total
    }

    /** Convenience for downloading a track to a scratch file. */
    fun downloadTo(playlistUrl: String, file: File, progress: Progress? = null): Long =
        file.outputStream().buffered().use { download(playlistUrl, it, progress) }
}
