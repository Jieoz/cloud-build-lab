package com.jiesa.xvideocatcher

/**
 * Extracts a tweet id from text produced by X — a shared link, a copied link, an intent extra.
 *
 * Why this is its own class rather than a regex inside a caller: the same extraction is needed
 * by two independent consumers (the recon hooks that record what X exposes, and any
 * share-target that receives a link), and a download aimed at the wrong id silently fetches
 * the wrong post. One implementation, one test suite.
 *
 * A tweet id cannot be derived from a media id even though both are snowflakes. Measured on a
 * real post, tweet `1906874341842706592` pairs with media `1906874143078838272` — a gap of
 * 198763868320, about 47 seconds of id space, which is a property of when the upload happened
 * and not a constant. Anything that needs a tweet id has to read one.
 */
object TweetUrl {

    /**
     * Host is anchored to a `/` or start-of-string boundary so `x.com.evil.tld` and
     * `notx.com` cannot match — this text arrives from the host process and, in the share
     * case, ultimately from a post author, so a lookalike host would otherwise make the
     * module fetch on someone else's behalf.
     *
     * The id is bounded to 5..25 digits: snowflakes are 18-19 digits today, and an unbounded
     * `\d+` would happily swallow a tracking number that follows.
     */
    private val statusUrl = Regex(
        """(?:^|[/@\s(])(?:mobile\.)?(?:twitter|x)\.com/[A-Za-z0-9_]{1,20}/status/(\d{5,25})"""
    )

    /** Returns the first tweet id in [text], or null when there is none. */
    fun idFrom(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val prepared = if (text.startsWith("http")) text.substringAfter("//", text) else text
        return statusUrl.find(prepared)?.groupValues?.get(1)
    }

    /** All distinct ids in [text], oldest-first by appearance. Used by the recon recorder. */
    fun allIdsIn(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val prepared = if (text.startsWith("http")) text.substringAfter("//", text) else text
        return statusUrl.findAll(prepared).map { it.groupValues[1] }.distinct().toList()
    }
}
