package com.yourtube.core.player

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.palette.graphics.Palette

/**
 * YT-0076 — small in-process LRU cache of palette-extracted dominant colors keyed by `videoId`.
 *
 * Palette extraction is non-trivial (a synchronous bitmap scan) and the
 * `MediaNotification.Provider` re-builds the notification on every metadata refresh, every
 * play/pause, and every position update. Caching by `videoId` keeps that work to once-per-track.
 *
 * The cache is intentionally tiny (`maxEntries = 32`) — large enough to cover a typical queue
 * plus the next-track pre-warm without keeping bitmaps around indirectly. Eviction is plain
 * insertion-order LRU using `LinkedHashMap`. The cache is thread-safe via `synchronized`; the
 * provider may call `get` from the player application thread and `put` from a background
 * worker, both of which are infrequent.
 */
internal class PaletteColorCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val lock = Any()

    // accessOrder = true → reads count as access for LRU ordering.
    private val entries = object : LinkedHashMap<String, Int>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > maxEntries
    }

    @ColorInt
    fun get(videoId: String): Int? {
        if (videoId.isEmpty()) return null
        synchronized(lock) {
            return entries[videoId]
        }
    }

    fun put(videoId: String, @ColorInt color: Int) {
        if (videoId.isEmpty()) return
        synchronized(lock) {
            entries[videoId] = color
        }
    }

    /** Returns the cached color, otherwise extracts it, caches it, and returns the new value. */
    @ColorInt
    fun getOrExtract(videoId: String, bitmap: Bitmap): Int? {
        get(videoId)?.let { return it }
        val color = extractDominantColor(bitmap) ?: return null
        put(videoId, color)
        return color
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 32

        /**
         * Extract a single representative color from a bitmap. Preference order matches the
         * task brief: dominant → vibrant → muted. Returns `null` only when Palette finds no
         * usable swatches at all (e.g. fully transparent bitmap).
         */
        @ColorInt
        fun extractDominantColor(bitmap: Bitmap): Int? {
            val palette = runCatching { Palette.from(bitmap).generate() }.getOrNull()
                ?: return null
            val swatch = palette.dominantSwatch
                ?: palette.vibrantSwatch
                ?: palette.mutedSwatch
                ?: palette.darkVibrantSwatch
                ?: palette.darkMutedSwatch
            return swatch?.rgb
        }
    }
}
