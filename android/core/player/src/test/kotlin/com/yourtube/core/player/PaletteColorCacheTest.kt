package com.yourtube.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * YT-0076 — locks the LRU semantics of [PaletteColorCache]. The cache backs the
 * [ColorizedMediaNotificationProvider] hot-path; eviction has to evict on insertion-order LRU
 * with the most-recently-read entry kept hot, otherwise rapid back-and-forth between two
 * tracks would re-extract palette colors on every notification refresh.
 */
class PaletteColorCacheTest {

    @Test
    fun `get returns null for unknown videoId`() {
        val cache = PaletteColorCache(maxEntries = 4)
        assertNull(cache.get("v1"))
    }

    @Test
    fun `put then get returns the cached color`() {
        val cache = PaletteColorCache(maxEntries = 4)
        cache.put("v1", 0xFF112233.toInt())
        assertEquals(0xFF112233.toInt(), cache.get("v1"))
    }

    @Test
    fun `put with empty videoId is ignored`() {
        val cache = PaletteColorCache(maxEntries = 4)
        cache.put("", 0xFFAABBCC.toInt())
        assertEquals(0, cache.size())
    }

    @Test
    fun `eldest entry is evicted when capacity is exceeded`() {
        val cache = PaletteColorCache(maxEntries = 2)
        cache.put("v1", 0x11)
        cache.put("v2", 0x22)
        cache.put("v3", 0x33) // should evict v1 (eldest)

        assertNull(cache.get("v1"))
        assertEquals(0x22, cache.get("v2"))
        assertEquals(0x33, cache.get("v3"))
    }

    @Test
    fun `reading an entry promotes it so it is not evicted next`() {
        val cache = PaletteColorCache(maxEntries = 2)
        cache.put("v1", 0x11)
        cache.put("v2", 0x22)
        // Access v1 — accessOrder LRU should now treat v2 as eldest.
        assertEquals(0x11, cache.get("v1"))
        cache.put("v3", 0x33)

        assertEquals(0x11, cache.get("v1"), "v1 was just read, must survive the eviction.")
        assertNull(cache.get("v2"), "v2 should have been evicted as the eldest by access order.")
        assertEquals(0x33, cache.get("v3"))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = PaletteColorCache(maxEntries = 4)
        cache.put("v1", 0x11)
        cache.put("v2", 0x22)
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("v1"))
    }

    @Test
    fun `put overwrites the previous color for the same videoId without growing`() {
        val cache = PaletteColorCache(maxEntries = 4)
        cache.put("v1", 0x11)
        cache.put("v1", 0x22)
        assertEquals(0x22, cache.get("v1"))
        assertEquals(1, cache.size())
    }
}
