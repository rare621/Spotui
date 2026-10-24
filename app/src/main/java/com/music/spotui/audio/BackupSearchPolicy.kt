package com.music.spotui.audio

import java.text.Normalizer
import org.json.JSONObject

object BackupSearchPolicy {
    data class Target(val title: String, val artist: String, val durationMs: Int, val explicit: Boolean?)
    private fun words(value: String): Set<String> = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    private val alternates = setOf("remix", "live", "acoustic", "cover", "karaoke", "instrumental", "nightcore", "slowed", "sped", "mashup", "demo", "reverb")
    fun query(target: Target): String = "${MusicTitlePolicy.clean(target.title)} ${target.artist.substringBefore(',')}".trim().take(300)

    /** Search results are candidates only. Match the requested recording before extracting audio. */
    fun candidates(raw: String, target: Target): List<String> = runCatching {
        val expectedTitle = words(MusicTitlePolicy.clean(target.title))
        val primaryArtist = words(target.artist.substringBefore(','))
        if (expectedTitle.isEmpty() || primaryArtist.isEmpty()) return@runCatching emptyList()
        val primaryKey = primaryArtist.joinToString("")
        fun ownerMatches(value: String): Boolean = words(value).containsAll(primaryArtist) ||
            words(value).joinToString("").removeSuffix("vevo").removeSuffix("topic").removeSuffix("official") == primaryKey
        val allArtists = words(target.artist)
        val requested = words(target.title)
        val entries = JSONObject(raw).optJSONArray("entries") ?: return@runCatching emptyList()
        (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.mapNotNull { item ->
            val id = item.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) || item.optBoolean("is_live") ||
                item.optString("live_status") in setOf("is_live", "is_upcoming") ||
                item.optString("availability") in setOf("private", "premium_only", "subscriber_only")) return@mapNotNull null
            val title = item.optString("title")
            val titleWords = words(title)
            val evidence = titleWords + words(item.optString("channel")) + words(item.optString("uploader"))
            val knownOwner = ownerMatches(item.optString("channel")) || ownerMatches(item.optString("uploader"))
            if (!evidence.containsAll(primaryArtist) && !knownOwner) return@mapNotNull null
            if (((titleWords - requested) intersect alternates).isNotEmpty()) return@mapNotNull null
            if (target.explicit == true && ("clean" in titleWords || "censored" in titleWords || "radio edit" in title.lowercase())) return@mapNotNull null
            if (target.explicit == false && ("explicit" in titleWords || "uncensored" in titleWords)) return@mapNotNull null
            val titlePart = if (" - " in title && ownerMatches(title.substringBefore(" - "))) title.substringAfter(" - ") else title
            val cleanWords = words(MusicTitlePolicy.clean(titlePart)) - (allArtists - expectedTitle)
            if (!cleanWords.containsAll(expectedTitle)) return@mapNotNull null
            val overlap = expectedTitle.size.toDouble() / cleanWords.size.coerceAtLeast(1)
            if (overlap < 0.8) return@mapNotNull null
            val duration = item.optDouble("duration", 0.0)
            val expectedSec = target.durationMs / 1000.0
            val hasDuration = duration.isFinite() && duration > 0 && expectedSec > 0
            if (hasDuration && kotlin.math.abs(duration - expectedSec) > (expectedSec * 0.04).coerceIn(4.0, 10.0)) return@mapNotNull null
            if (!hasDuration && !knownOwner) return@mapNotNull null
            id to (overlap + if (hasDuration) 1.0 - kotlin.math.abs(duration - expectedSec) / expectedSec else 0.0)
        }.sortedByDescending { it.second }.map { it.first }.distinct().take(3)
    }.getOrDefault(emptyList())
}

/** A backup can search even when the primary returns no video IDs. Search and extraction stay distinct. */
internal object BackupCandidatePlanner {
    suspend fun <T> lookup(known: List<String>, search: suspend () -> List<String>, resolve: suspend (String) -> T?): T? {
        val tried = linkedSetOf<String>()
        known.firstOrNull()?.let { id -> tried += id; resolve(id)?.let { return it } }
        for (id in (search() + known.drop(1)).distinct()) {
            if (tried.size >= 3) break
            if (!tried.add(id)) continue
            resolve(id)?.let { return it }
        }
        return null
    }
}
