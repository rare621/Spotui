package com.music.spotui.audio

/** Memory-only preference for the independent resolver after a returned stream fails to play. */
internal object RejectedStreamRecovery {
    private val rejected = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }
    @Synchronized fun failed(query: String, now: Long = System.nanoTime()) { rejected[query] = now }
    @Synchronized fun preferBackup(query: String, now: Long = System.nanoTime()): Boolean {
        val time = rejected[query] ?: return false
        if (now - time !in 0..300_000_000_000L) { rejected.remove(query); return false }
        return true
    }
    @Synchronized fun recovered(query: String) { rejected.remove(query) }
}
