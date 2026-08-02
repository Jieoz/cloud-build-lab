package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for what the download button acts on.
 *
 * The behaviour under test is an inference — "the media the user is looking at is the media X
 * fetched most recently" — so these tests pin the parts that make the inference safe rather
 * than the inference itself.
 */
class MediaRegistryTest {

    @Before
    fun reset() = MediaRegistry.clear()

    @Test
    fun `latest master wins`() {
        MediaRegistry.rememberMaster("111", "https://video.twimg.com/amplify_video/111/pl/aaa.m3u8")
        MediaRegistry.rememberMaster("222", "https://video.twimg.com/amplify_video/222/pl/bbb.m3u8")
        assertEquals("222", MediaRegistry.latestVideo()?.mediaId)
    }

    /**
     * The failure this prevents: a video post also fetches a poster from pbs.twimg.com, and the
     * poster arrives *after* the playlist. A single "latest media" slot would therefore hand the
     * user a thumbnail every time they asked for a video.
     */
    @Test
    fun `video poster is not offered as a photo`() {
        MediaRegistry.rememberPhoto(
            "https://pbs.twimg.com/amplify_video_thumb/2083005054190297088/img/D9Ponp1yGlzps0nb.jpg"
        )
        MediaRegistry.rememberPhoto(
            "https://pbs.twimg.com/ext_tw_video_thumb/2083304240613888000/pu/img/abcdEFGH.jpg"
        )
        assertNull("posters must not become downloadable photos", MediaRegistry.latestPhoto())
    }

    @Test
    fun `real photo is remembered at full size`() {
        MediaRegistry.rememberPhoto("https://pbs.twimg.com/media/HN3SvIgawAA7t4A?format=jpg&name=large")
        val photo = MediaRegistry.latestPhoto()
        assertNotNull(photo)
        assertTrue("must be upgraded to full size, got $photo", photo!!.contains("name=4096x4096"))
    }

    /** A PNG must not come back as JPEG: forcing jpg re-encodes it to a fraction of the size. */
    @Test
    fun `photo format is preserved`() {
        MediaRegistry.rememberPhoto("https://pbs.twimg.com/media/HOmXCrJbQAA4YJp?format=png&name=large")
        assertTrue(MediaRegistry.latestPhoto()!!.contains("format=png"))
    }

    /**
     * Video and photo are tracked in separate slots, so a video post (playlist then poster)
     * still resolves to the video.
     */
    @Test
    fun `video and photo do not evict each other`() {
        MediaRegistry.rememberMaster("999", "https://video.twimg.com/amplify_video/999/pl/xyz.m3u8")
        MediaRegistry.rememberPhoto("https://pbs.twimg.com/media/HOkVIBvWwAEXXw7?format=jpg&name=large")
        assertEquals("999", MediaRegistry.latestVideo()?.mediaId)
        assertNotNull(MediaRegistry.latestPhoto())
    }

    /** Re-viewing an item makes it newest again; insertion order alone would not. */
    @Test
    fun `re-seen master becomes newest`() {
        MediaRegistry.rememberMaster("aaa", "https://video.twimg.com/amplify_video/aaa/pl/1.m3u8")
        MediaRegistry.rememberMaster("bbb", "https://video.twimg.com/amplify_video/bbb/pl/2.m3u8")
        MediaRegistry.rememberMaster("aaa", "https://video.twimg.com/amplify_video/aaa/pl/1.m3u8")
        assertEquals("aaa", MediaRegistry.latestVideo()?.mediaId)
    }

    /** Bounded: this map lives in X's heap, and a timeline scroll never stops fetching. */
    @Test
    fun `capacity is bounded`() {
        repeat(200) { i ->
            MediaRegistry.rememberMaster("$i", "https://video.twimg.com/amplify_video/$i/pl/k$i.m3u8")
        }
        assertEquals("199", MediaRegistry.latestVideo()?.mediaId)
    }

    @Test
    fun `empty registry offers nothing`() {
        assertNull(MediaRegistry.latestVideo())
        assertNull(MediaRegistry.latestPhoto())
        assertTrue(!MediaRegistry.hasAnything())
    }
}
