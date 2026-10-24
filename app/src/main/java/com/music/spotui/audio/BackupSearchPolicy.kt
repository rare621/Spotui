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
    data class SearchReport(val ids: List<String>, val rejected: Map<String, Int>)
    fun candidates(raw: String, target: Target): List<String> = inspect(raw, target).ids
    fun inspect(raw: String, target: Target): SearchReport {
        val rejected = linkedMapOf<String, Int>()
        fun reject(reason: String): Nothing? { rejected[reason] = (rejected[reason] ?: 0) + 1; return null }
        val ids = runCatching {
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
                item.optString("availability") in setOf("private", "premium_only", "subscriber_only")) return@mapNotNull reject("Unavailable result")
            val title = item.optString("title")
            val titleWords = words(title)
            val evidence = titleWords + words(item.optString("channel")) + words(item.optString("uploader"))
            val knownOwner = ownerMatches(item.optString("channel")) || ownerMatches(item.optString("uploader"))
            if (!evidence.containsAll(primaryArtist) && !knownOwner) return@mapNotNull reject("Artist mismatch")
            if (((titleWords - requested) intersect alternates).isNotEmpty()) return@mapNotNull reject("Alternate recording")
            if (target.explicit == true && ("clean" in titleWords || "censored" in titleWords || "radio edit" in title.lowercase())) return@mapNotNull reject("Edition mismatch")
            if (target.explicit == false && ("explicit" in titleWords || "uncensored" in titleWords)) return@mapNotNull reject("Edition mismatch")
            val titlePart = if (" - " in title && ownerMatches(title.substringBefore(" - "))) title.substringAfter(" - ") else title
            val cleanWords = (words(MusicTitlePolicy.clean(titlePart)) - (allArtists - expectedTitle)) -
                (setOf("explicit", "uncensored", "clean", "censored") - expectedTitle)
            if (!cleanWords.containsAll(expectedTitle)) return@mapNotNull reject("Title mismatch")
            val overlap = expectedTitle.size.toDouble() / cleanWords.size.coerceAtLeast(1)
            if (overlap < 0.8) return@mapNotNull reject("Title mismatch")
            val duration = item.optDouble("duration", 0.0)
            val expectedSec = target.durationMs / 1000.0
            val hasDuration = duration.isFinite() && duration > 0 && expectedSec > 0
            // Only an exact title on the artist's channel may tolerate a video intro/outro.
            // Prefer the album-length audio; never loosen artist or alternate-version checks.
            val officialVideo = knownOwner && overlap == 1.0 &&
                Regex("\\b(?:official\\s+)?(?:music\\s+)?video\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)
            val tolerance = if (officialVideo) (expectedSec * 0.10).coerceIn(8.0, 30.0)
                else (expectedSec * 0.04).coerceIn(4.0, 12.0)
            if (hasDuration && kotlin.math.abs(duration - expectedSec) > tolerance) return@mapNotNull reject("Duration mismatch")
            if (!hasDuration && !knownOwner) return@mapNotNull reject("Missing duration and artist channel")
            id to (overlap + if (hasDuration) 1.0 - kotlin.math.abs(duration - expectedSec) / expectedSec else 0.0)
        }.sortedByDescending { it.second }.map { it.first }.distinct().take(3)
        }.getOrElse { reject("Unreadable search response"); emptyList() }
        return SearchReport(ids, rejected)
    }
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
