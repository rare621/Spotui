package com.music.spotui.audio

/** Small memory-only header registry shared by the player, preloader, browser and downloads. */
object StreamRequestHeaders {
    private data class HeaderRecord(val headers: Map<String, String>, val at: Long)
    private val entries = object : LinkedHashMap<String, HeaderRecord>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HeaderRecord>?) = size > 64
    }
    @Synchronized fun put(url: String, headers: Map<String, String>, now: Long = System.nanoTime()) {
        entries[url] = HeaderRecord(YtDlpStreamPolicy.safeHeaders(headers), now)
    }
    @Synchronized fun get(url: String, now: Long = System.nanoTime()): Map<String, String>? {
        val item = entries[url] ?: return null
        val age = now - item.at
        if (age < 0 || age > 30L * 60 * 1_000_000_000) { entries.remove(url); return null }
        return item.headers
    }
}
