package com.jiesa.xvideocatcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe hooks sit on X's network hot path, so the filter has to be both correct
 * and cheap. These cases lock in what must pass and — just as important — what must
 * be dropped, because a filter that lets avatars through floods the log and makes
 * the real playback URL impossible to spot.
 */
class MediaUrlsTest {

    @Test
    fun `hls manifest on video cdn is interesting`() {
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/ext_tw_video/1234/pu/pl/720x1280/abcd.m3u8"
            )
        )
    }

    @Test
    fun `progressive mp4 on video cdn is interesting`() {
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/ext_tw_video/1234/pu/vid/720x1280/abcd.mp4"
            )
        )
    }

    @Test
    fun `amplify video path is interesting even on another host`() {
        assertTrue(MediaUrls.isInteresting("https://cdn.example.net/amplify_video/999/vid/a.mp4"))
    }

    @Test
    fun `hls segment is interesting`() {
        assertTrue(MediaUrls.isInteresting("https://video.twimg.com/ext_tw_video/1/pu/seg/1.ts"))
    }

    @Test
    fun `graphql and profile images are dropped`() {
        assertFalse(MediaUrls.isInteresting("https://x.com/i/api/graphql/abc/TweetDetail"))
        assertFalse(MediaUrls.isInteresting("https://pbs.twimg.com/profile_images/1/avatar.jpg"))
        assertFalse(MediaUrls.isInteresting("https://api.x.com/1.1/jot/client_event.json"))
    }

    @Test
    fun `non http and empty inputs are dropped`() {
        assertFalse(MediaUrls.isInteresting(""))
        assertFalse(MediaUrls.isInteresting("file:///data/user/0/com.twitter.android/cache/a.mp4"))
        assertFalse(MediaUrls.isInteresting("content://media/external/video/1"))
    }

    @Test
    fun `manifest is distinguished from segment`() {
        assertTrue(MediaUrls.isManifest("https://video.twimg.com/a/pl/720/x.m3u8"))
        assertFalse(
            MediaUrls.isManifest("https://video.twimg.com/ext_tw_video/1/vid/720x1280/a.mp4")
        )
    }
}
