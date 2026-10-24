package com.music.spotui.audio

import java.net.URI
import org.json.JSONObject

/** Accept one exact matched YouTube recording, never a playlist, video or manifest. */
object YtDlpStreamPolicy {
    data class Audio(val url: String, val mime: String, val codec: String, val bitrate: Int,
        val length: Long?, val durationMs: Long?, val headers: Map<String, String>)
    fun parse(raw: String, videoId: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Audio? = runCatching {
        val json = JSONObject(raw)
        if (json.optString("id") != videoId || json.has("entries") || json.optBoolean("is_live") ||
            json.optString("vcodec") != "none" || json.optString("acodec").let { it.isBlank() || it == "none" }) return@runCatching null
        val url = json.getString("url")
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme != "https" || !host.endsWith(".googlevideo.com") || uri.userInfo != null ||
            uri.port !in listOf(-1, 443) || json.optString("protocol") != "https") return@runCatching null
        val expires = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }
            ?.substringAfter('=')?.toLongOrNull()
        if (expires != null && expires <= nowSeconds + 30) return@runCatching null
        val codec = json.optString("acodec")
        val mime = when (json.optString("ext")) {
            "m4a" -> "audio/mp4"
            "webm" -> "audio/webm"
            else -> return@runCatching null
        }
        val headers = json.optJSONObject("http_headers")?.let { h ->
            h.keys().asSequence().associateWith { h.optString(it) }
        }.orEmpty()
        Audio(url, mime, codec, ((json.optDouble("abr", json.optDouble("tbr", 0.0))) * 1000)
            .takeIf { it.isFinite() && it > 0 }?.toInt() ?: 0,
            json.optLong("filesize", 0).takeIf { it > 0 },
            json.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }?.times(1000)?.toLong(),
            safeHeaders(headers))
    }.getOrNull()

    fun safeHeaders(headers: Map<String, String>): Map<String, String> = headers.filter { (key, value) ->
        key.lowercase() in setOf("user-agent", "referer", "origin", "accept", "accept-language") &&
            value.length <= 4096 && value.none { it == '\r' || it == '\n' || it == '\u0000' }
    }

    /** The saved account cookie is only passed to YouTube, in a private short-lived file. */
    fun cookieJar(cookie: String): String = "# Netscape HTTP Cookie File\n" + cookie.split(';').mapNotNull { part ->
        val name = part.substringBefore('=').trim()
        val value = part.substringAfter('=', "").trim()
        if ('=' !in part || !name.matches(Regex("[A-Za-z0-9_-]+")) ||
            value.any { it == '\r' || it == '\n' || it == '\t' || it == '\u0000' }) null
        else ".youtube.com\tTRUE\t/\tTRUE\t0\t$name\t$value\n"
    }.joinToString("")
}
