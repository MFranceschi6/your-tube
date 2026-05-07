package com.yourtube.core.network

internal class BoundedMemoryCache<K : Any, V : Any>(
    private val maxSize: Int,
) {
    init {
        require(maxSize > 0) { "maxSize must be greater than 0" }
    }

    private val entries = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = entries[key]

    // get + put are separately synchronised; a concurrent caller may insert the same key twice.
    // Benign: both writes produce an equivalent value and the LRU order is self-correcting.
    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
    }
}
