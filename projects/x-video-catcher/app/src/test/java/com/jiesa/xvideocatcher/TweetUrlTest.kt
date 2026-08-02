package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for pulling a tweet id out of arbitrary host text.
 *
 * This is load-bearing for the whole share-based design: the syndication endpoint that
 * returns progressive MP4 renditions is keyed by *tweet* id, and every URL the probe has
 * captured so far carries only a *media* id. The two cannot be converted — both are
 * snowflakes, but the offset between them is not constant (measured: a 198763868320 gap on
 * one real post, i.e. 47 seconds of id space), so a media id cannot be turned into a tweet
 * id by arithmetic. Reading the id out of text X itself produced is the only route.
 */
class TweetUrlTest {

    @Test
    fun `canonical share url`() {
        assertEquals(
            "1906874341842706592",
            TweetUrl.idFrom("https://x.com/SpaceX/status/1906874341842706592"),
        )
    }

    @Test
    fun `legacy twitter host still works`() {
        assertEquals(
            "1906874341842706592",
            TweetUrl.idFrom("https://twitter.com/SpaceX/status/1906874341842706592"),
        )
    }

    /** X appends tracking parameters to shared links; they must not end up in the id. */
    @Test
    fun `query parameters are not part of the id`() {
        assertEquals(
            "2057146860171645040",
            TweetUrl.idFrom("https://x.com/i/status/2057146860171645040?s=46&t=abcDEF"),
        )
    }

    /** A real ACTION_SEND body is a sentence with the URL embedded, not a bare URL. */
    @Test
    fun `id is found inside surrounding text`() {
        val shared = "Meet the framonauts https://x.com/SpaceX/status/1906874341842706592 via @X"
        assertEquals("1906874341842706592", TweetUrl.idFrom(shared))
    }

    @Test
    fun `photo permalink carries the tweet id too`() {
        assertEquals(
            "1906874341842706592",
            TweetUrl.idFrom("https://x.com/SpaceX/status/1906874341842706592/photo/1"),
        )
    }

    @Test
    fun `video permalink carries the tweet id too`() {
        assertEquals(
            "1906874341842706592",
            TweetUrl.idFrom("https://x.com/SpaceX/status/1906874341842706592/video/1"),
        )
    }

    /**
     * The failure this prevents: a media URL is the *most* common string in this process, and
     * treating the number in it as a tweet id sends a media id to the syndication endpoint,
     * which answers 404 — verified against two real media ids.
     */
    @Test
    fun `media url is not mistaken for a tweet url`() {
        assertNull(
            TweetUrl.idFrom(
                "https://video.twimg.com/amplify_video/2083052041300320256/pl/Kshvg9LNsl3i6Kkc.m3u8"
            )
        )
        assertNull(
            TweetUrl.idFrom(
                "https://pbs.twimg.com/media/HOmm3zJbYAAiFy4?format=jpg&name=medium"
            )
        )
    }

    /** A lookalike host must not match, or the module would fetch on a phisher's behalf. */
    @Test
    fun `lookalike hosts do not match`() {
        assertNull(TweetUrl.idFrom("https://notx.com/a/status/123456789012345"))
        assertNull(TweetUrl.idFrom("https://x.com.evil.tld/a/status/123456789012345"))
    }

    @Test
    fun `text without any url yields null`() {
        assertNull(TweetUrl.idFrom("just setting up my twttr"))
        assertNull(TweetUrl.idFrom(""))
    }

    /** Shortened links have no id yet; reporting one would be a fabricated result. */
    @Test
    fun `t co shortlink yields null`() {
        assertNull(TweetUrl.idFrom("https://t.co/TtlqpsCjIA"))
    }
}
