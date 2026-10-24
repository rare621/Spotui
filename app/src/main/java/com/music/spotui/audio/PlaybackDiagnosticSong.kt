package com.music.spotui.audio

import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/** Display metadata only: never use a playback query, file path, request header or stream URL. */
object DiagnosticSongLabel {
    fun sanitize(value: String): String = value
        .replace(Regex("(?i)(?:https?://|file://|content://|spotify:)[^\\s]+"), "[link removed]")
        .replace(Regex("(?i)(?:bearer\\s+|(?:cookie|token|authorization)\\s*[:=]\\s*)[^\\s]+"), "[redacted]")
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ").trim().take(220)
    fun create(title: String, artist: String): String = sanitize(listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" — "))
}

/** Scoped to each lookup coroutine, so next-song preparation cannot rename the current song's events. */
object PlaybackDiagnosticSong {
    private val name = ThreadLocal<String>()
    fun context(label: String): CoroutineContext = name.asContextElement(DiagnosticSongLabel.sanitize(label))
    fun current(): String = name.get().orEmpty()
}
