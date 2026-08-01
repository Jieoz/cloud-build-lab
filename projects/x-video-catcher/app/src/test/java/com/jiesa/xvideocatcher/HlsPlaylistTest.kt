package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests driven by playlist bodies **fetched from video.twimg.com** during the
 * 2026-08-01 capture and committed under `src/test/resources/fixtures/` (26 masters plus
 * one video and one audio variant).
 *
 * Real bodies rather than hand-written ones, for a specific reason: the first version of
 * the attribute splitter looked correct against invented samples and was wrong against
 * every real master, because `CODECS="mp4a.40.2,avc1.64001F"` contains a comma inside its
 * quotes. A sample set written by the same author as the parser agrees with the parser's
 * mistakes.
 */
class HlsPlaylistTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name"
        }.bufferedReader().readText()

    private fun masterFixtures(): List<Pair<String, String>> {
        // The names are known from the capture; reading the directory is not portable
        // across jar/classes layouts, so the index is explicit.
        val ids = fixture("captured_urls.json")
        assertTrue("captured url set should be non-trivial", ids.length > 1000)
        return MASTERS.map { it to fixture("master_$it.m3u8") }
    }

    // --- parsing ----------------------------------------------------------------

    @Test
    fun `every captured master parses into at least one video variant`() {
        val all = masterFixtures()
        assertEquals("fixture count", 26, all.size)
        for ((name, body) in all) {
            val master = HlsPlaylist.parseMaster("https://video.twimg.com/$name/pl/x.m3u8", body)
            assertNotNull("$name should parse as a master", master)
            assertTrue(
                "$name should advertise at least one video variant",
                master!!.videoVariants.isNotEmpty(),
            )
            // Every variant must resolve to an absolute URL, or a fetch cannot use it.
            for (v in master.videoVariants) {
                assertTrue("$name variant not absolute: ${v.url}", v.url.startsWith("https://"))
                assertTrue("$name variant has no resolution", v.width > 0 && v.height > 0)
            }
        }
    }

    @Test
    fun `codecs attribute containing a comma does not corrupt the audio group`() {
        // This is the case a hand-written fixture would have missed.
        val attrs = HlsPlaylist.parseAttributes(
            """AVERAGE-BANDWIDTH=864766,BANDWIDTH=910102,RESOLUTION=720x960,CODECS="mp4a.40.2,avc1.64001F",AUDIO="audio-128000""""
        )
        assertEquals("mp4a.40.2,avc1.64001F", attrs["CODECS"])
        assertEquals("audio-128000", attrs["AUDIO"])
        assertEquals("720x960", attrs["RESOLUTION"])
    }

    @Test
    fun `a variant playlist is not accepted as a master`() {
        val body = fixture("variant_1080.m3u8")
        assertFalse(HlsPlaylist.isMasterBody(body))
        assertNull(HlsPlaylist.parseMaster("https://video.twimg.com/x/pl/y.m3u8", body))
    }

    @Test
    fun `variant playlist yields init segment first then media segments in order`() {
        val url =
            "https://video.twimg.com/amplify_video/2083502029440507905/pl/avc1/1920x1080/V1ttE4QoQSgL8lGt.m3u8"
        val media = HlsPlaylist.parseMedia(url, fixture("variant_1080.m3u8"))

        assertNotNull("init segment (EXT-X-MAP) is required to assemble fMP4", media.initSegment)
        assertTrue(media.initSegment!!.endsWith(".mp4"))
        assertTrue("expected many segments, got ${media.segments.size}", media.segments.size > 5)
        assertTrue(media.segments.all { it.startsWith("https://video.twimg.com/") })
        assertTrue("segments must be .m4s", media.segments.all { it.endsWith(".m4s") })

        // Order is load-bearing: segment ranges must ascend, since concatenating them out
        // of order produces a file that plays but jumps.
        val starts = media.segments.mapNotNull {
            Regex("""/vid/avc1/(\d+)/""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        assertEquals(media.segments.size, starts.size)
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun `audio variant playlist parses as segments too`() {
        val media = HlsPlaylist.parseMedia(
            "https://video.twimg.com/amplify_video/2079090871887396864/pl/mp4a/128000/_62BD5_OuRmsPpp3.m3u8",
            fixture("variant_audio128k.m3u8"),
        )
        assertTrue(media.segments.isNotEmpty())
        assertNotNull(media.initSegment)
    }

    // --- selection --------------------------------------------------------------

    @Test
    fun `best audio is the highest bitrate offered, not the one paired with the video`() {
        // The measured defect: X fetched only audio-32000 on 8 of 10 videos while the
        // master advertised 128000. Selection must read the master, not the capture.
        for ((name, body) in masterFixtures()) {
            val master = HlsPlaylist.parseMaster("https://video.twimg.com/$name/pl/x.m3u8", body)!!
            if (master.audioTracks.size < 2) continue
            val best = master.bestAudio!!
            assertEquals(
                "$name should select the max-bitrate audio track",
                master.audioTracks.maxOf { it.bitrate },
                best.bitrate,
            )
            assertTrue("$name best audio should beat 32000", best.bitrate > 32000)
        }
    }

    @Test
    fun `best video is chosen by pixel area so orientation does not decide it`() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=1280x720,CODECS="avc1"
            /a/pl/avc1/1280x720/k.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900,RESOLUTION=720x1280,CODECS="avc1"
            /a/pl/avc1/720x1280/k.m3u8
        """.trimIndent()
        val master = HlsPlaylist.parseMaster("https://video.twimg.com/a/pl/m.m3u8", body)!!
        // Equal area: the higher bandwidth wins rather than whichever came first.
        assertEquals(900L, master.bestVideo!!.bandwidth)
    }

    @Test
    fun `host-absolute and relative uris both resolve against the playlist url`() {
        val base = "https://video.twimg.com/amplify_video/1/pl/master.m3u8"
        assertEquals(
            "https://video.twimg.com/amplify_video/1/pl/avc1/x.m3u8",
            HlsPlaylist.resolve(base, "/amplify_video/1/pl/avc1/x.m3u8"),
        )
        assertEquals(
            "https://video.twimg.com/amplify_video/1/pl/avc1/x.m3u8",
            HlsPlaylist.resolve(base, "avc1/x.m3u8"),
        )
        assertEquals(
            "https://cdn.example/x.m3u8",
            HlsPlaylist.resolve(base, "https://cdn.example/x.m3u8"),
        )
    }

    private companion object {
        /**
         * Fixture names, listed from the committed files. Both media kinds are present on
         * purpose: `ext_tw_video` (user uploads) inserts a `pu/` path segment that
         * `amplify_video` does not, and that difference has broken URL classification
         * before.
         */
        val MASTERS = listOf(
            "amplify_video_1965301871540936704",
            "amplify_video_1969263052999835648",
            "amplify_video_1969497427691651075",
            "amplify_video_2061640386943254528",
            "amplify_video_2073760975543754752",
            "amplify_video_2074118738748796928",
            "amplify_video_2076928325608726528",
            "amplify_video_2078379275015892992",
            "amplify_video_2079090871887396864",
            "amplify_video_2079619587365289984",
            "amplify_video_2079875863139753985",
            "amplify_video_2082586543957651456",
            "amplify_video_2082966766549168128",
            "amplify_video_2083102718865108992",
            "amplify_video_2083104365813075968",
            "amplify_video_2083179106712096768",
            "amplify_video_2083179881651941376",
            "amplify_video_2083310061368053760",
            "amplify_video_2083408995981914112",
            "amplify_video_2083491351203868672",
            "amplify_video_2083502029440507905",
            "amplify_video_2083509343769792512",
            "amplify_video_2083532122950860800",
            "ext_tw_video_2028482401027162112",
            "ext_tw_video_2082862956648513536",
            "ext_tw_video_2083304240613888000",
        )
    }
}
