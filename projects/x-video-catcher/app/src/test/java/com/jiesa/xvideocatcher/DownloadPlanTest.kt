package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planning tests run against the **entire captured URL set** (488 distinct URLs, 33 videos)
 * committed as `fixtures/captured_urls.json`, not a chosen subset.
 *
 * The whole set matters here: the 7 videos with no captured master are the case the plan
 * exists to handle honestly, and they only appear if nothing is filtered out first.
 */
class DownloadPlanTest {

    private fun capturedUrls(): List<String> {
        val json = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/captured_urls.json")
        ) { "missing captured_urls.json" }.bufferedReader().readText()
        // Minimal reader: the file is a flat array of strings, and adding a JSON library to
        // the test classpath for one fixture is not worth it.
        return Regex("\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `capture fixture is the full set`() {
        val urls = capturedUrls()
        assertEquals(488, urls.size)
    }

    @Test
    fun `every captured video is classified and none is silently dropped`() {
        val urls = capturedUrls()
        val plans = DownloadPlan.from(urls)

        // 33 videos were captured; every one must produce exactly one plan.
        assertEquals(33, plans.size)
        assertEquals("plans must be unique per media id", 33, plans.map { it.mediaId }.toSet().size)

        val ready = plans.filterIsInstance<DownloadPlan.Plan.Ready>()
        val needsMaster = plans.filterIsInstance<DownloadPlan.Plan.NeedsMaster>()
        // The documented gap, now expressed as an assertion rather than a README note.
        assertEquals(26, ready.size)
        assertEquals(7, needsMaster.size)
    }

    @Test
    fun `ready plans point at a master playlist url`() {
        val plans = DownloadPlan.from(capturedUrls()).filterIsInstance<DownloadPlan.Plan.Ready>()
        assertTrue(plans.isNotEmpty())
        for (p in plans) {
            assertTrue("not a master: ${p.masterUrl}", MediaUrls.isMasterPlaylist(p.masterUrl))
            assertEquals(p.mediaId, MediaUrls.mediaId(p.masterUrl))
        }
    }

    @Test
    fun `videos without a master are reported as such instead of guessing one`() {
        val plans = DownloadPlan.from(capturedUrls())
            .filterIsInstance<DownloadPlan.Plan.NeedsMaster>()
        assertEquals(7, plans.size)
        for (p in plans) {
            // Whatever fallback is offered must be a real captured video variant, never a
            // constructed URL: master keys are random and unreconstructable.
            p.bestCapturedVariant?.let { v ->
                assertTrue("fallback must be a captured url", capturedUrls().contains(v))
                assertFalse("fallback must not be a master", MediaUrls.isMasterPlaylist(v))
                assertNotNull("fallback must carry a resolution", MediaUrls.resolution(v))
                assertFalse("fallback must not be an audio-only playlist", MediaUrls.isAudioTrack(v))
            }
        }
    }

    @Test
    fun `a segment-only capture is reported as having no usable fallback`() {
        // Real shape from the capture: segments arrived, the playlist never did. Segment
        // keys are random per segment, so the missing ones cannot be enumerated and the
        // video genuinely cannot be assembled — the plan must say so.
        val segmentsOnly = listOf(
            "https://video.twimg.com/amplify_video/999/vid/avc1/0/3000/1920x1080/aaaaaaaaaaaaaaaa.m4s",
            "https://video.twimg.com/amplify_video/999/vid/avc1/3000/6000/1920x1080/bbbbbbbbbbbbbbbb.m4s",
        )
        val plan = DownloadPlan.from(segmentsOnly).single()
        val needs = plan as DownloadPlan.Plan.NeedsMaster
        assertNull(needs.bestCapturedVariant)
        assertFalse(needs.hasDegradedFallback)
        assertEquals(2, needs.capturedSegments)
    }

    @Test
    fun `photos are not planned as videos`() {
        val plans = DownloadPlan.from(
            listOf("https://pbs.twimg.com/media/AbCdEf123.jpg?format=jpg&name=small")
        )
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `fetch selects best video and best audio from the master`() {
        val body = """
            #EXTM3U
            #EXT-X-MEDIA:NAME="Audio",TYPE=AUDIO,GROUP-ID="audio-32000",AUTOSELECT=YES,URI="/amplify_video/1/pl/mp4a/32000/a.m3u8"
            #EXT-X-MEDIA:NAME="Audio",TYPE=AUDIO,GROUP-ID="audio-128000",AUTOSELECT=YES,URI="/amplify_video/1/pl/mp4a/128000/b.m3u8"

            #EXT-X-STREAM-INF:BANDWIDTH=200,RESOLUTION=320x568,CODECS="mp4a.40.2,avc1.4D401E",AUDIO="audio-32000"
            /amplify_video/1/pl/avc1/320x568/low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000,RESOLUTION=1080x1920,CODECS="mp4a.40.2,avc1.640028",AUDIO="audio-32000"
            /amplify_video/1/pl/avc1/1080x1920/high.m3u8
        """.trimIndent()
        val master = HlsPlaylist.parseMaster("https://video.twimg.com/amplify_video/1/pl/m.m3u8", body)!!
        val fetch = DownloadPlan.fetchFor("1", master)!!

        assertEquals(1080, fetch.width)
        assertEquals(1920, fetch.height)
        // Note both variants declare AUDIO="audio-32000". Honouring that pairing would ship
        // 32 kbps audio when 128 kbps is offered, which is the measured defect.
        assertEquals(128000, fetch.audioBitrate)
        assertTrue(fetch.audioPlaylist!!.contains("/mp4a/128000/"))
        assertFalse(fetch.isSilent)
    }

    @Test
    fun `a master with no audio rendition yields a fetch marked silent`() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=200,RESOLUTION=320x568,CODECS="avc1.4D401E"
            /tweet_video/1/pl/avc1/320x568/low.m3u8
        """.trimIndent()
        val master = HlsPlaylist.parseMaster("https://video.twimg.com/tweet_video/1/pl/m.m3u8", body)!!
        val fetch = DownloadPlan.fetchFor("1", master)!!
        assertTrue("GIF-style uploads have no audio track", fetch.isSilent)
        assertEquals(0, fetch.audioBitrate)
    }
}
