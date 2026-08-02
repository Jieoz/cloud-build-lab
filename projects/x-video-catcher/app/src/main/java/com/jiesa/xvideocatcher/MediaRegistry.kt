package com.jiesa.xvideocatcher

/**
 * Remembers the media URLs seen most recently inside the host process, so a download
 * button has something to act on the moment it is tapped.
 *
 * Why this exists at all: the download button lives in X's own "more" menu, and by the time
 * it is tapped there is no way to ask X *which* tweet is open — the app is R8-obfuscated and
 * pairip-wrapped, so there is no readable view model to interrogate. What we do have is the
 * network layer, which our hooks already see. The media the user is looking at is, in
 * practice, the media X most recently fetched.
 *
 * That is an inference, so the shape here is chosen to keep it honest:
 *  - Entries are keyed by CDN identity and ordered most-recent-first, so "what is on screen"
 *    resolves to the newest entry rather than a random one.
 *  - Capacity is bounded. A timeline scroll fetches media continuously; an unbounded map in
 *    the host process is a leak in someone else's app, which is not ours to spend.
 *  - Video masters and photos are tracked separately. A video post also fetches a poster
 *    image from `pbs.twimg.com`, so a single "latest media" slot would hand the user a
 *    thumbnail when they asked for the video — the poster always arrives *after* the
 *    playlist, so it would win on recency every time.
 *
 * All access is synchronised: these callbacks arrive on X's network threads, and the menu
 * reads on the main thread.
 */
object MediaRegistry {

    /** Enough to cover a viewer session; small enough to be invisible in X's heap. */
    private const val CAPACITY = 32

    private val masters = LinkedHashMap<String, String>()
    private val photos = LinkedHashMap<String, String>()

    /** A video whose master playlist was captured, so every rendition is reachable. */
    data class Video(val mediaId: String, val masterUrl: String)

    fun rememberMaster(mediaId: String, masterUrl: String) = synchronized(this) {
        put(masters, mediaId, masterUrl)
    }

    /**
     * Records a photo. The URL is normalised to the full-size rendering on the way in, so
     * whichever size X happened to request, what we hold is what should be saved.
     *
     * Video posters are rejected here rather than filtered on read: `amplify_video_thumb`
     * and `ext_tw_video_thumb` are kept by [MediaUrls] on purpose (they identify a video
     * post in the probe log) but they are not photos a user asked to download, and letting
     * one in would make the photo button save a poster while a video is on screen.
     */
    fun rememberPhoto(url: String) = synchronized(this) {
        if (POSTER.containsMatchIn(url)) return
        val key = MediaUrls.photoKey(url) ?: return
        put(photos, key, MediaUrls.highestQualityPhoto(url))
    }

    private val POSTER = Regex(
        """/(tweet_video_thumb|ext_tw_video_thumb|amplify_video_thumb)/""",
        RegexOption.IGNORE_CASE,
    )

    fun latestVideo(): Video? = synchronized(this) {
        val id = masters.keys.lastOrNull() ?: return null
        Video(id, masters[id] ?: return null)
    }

    fun latestPhoto(): String? = synchronized(this) { photos.keys.lastOrNull()?.let { photos[it] } }

    fun hasAnything(): Boolean = synchronized(this) { masters.isNotEmpty() || photos.isNotEmpty() }

    fun clear() = synchronized(this) {
        masters.clear()
        photos.clear()
    }

    /**
     * Re-inserts on repeat so a re-viewed item becomes the newest again; LinkedHashMap keeps
     * insertion order, not access order, so a plain `put` on an existing key would leave a
     * re-opened video ranked behind whatever was scrolled past since.
     */
    private fun put(into: LinkedHashMap<String, String>, key: String, value: String) {
        into.remove(key)
        into[key] = value
        while (into.size > CAPACITY) {
            val oldest = into.keys.firstOrNull() ?: break
            into.remove(oldest)
        }
    }
}
