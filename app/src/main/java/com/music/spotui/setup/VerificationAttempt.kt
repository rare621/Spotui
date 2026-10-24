package com.music.spotui.setup

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** One-use grants belong to exactly one active attempt. Never retain the grant. */
class VerificationAttempt {
    data class Ticket(val generation: Int, val state: String)
    private var generation = 0
    private var current: Ticket? = null
    private var exchanging = false
    @Synchronized fun begin(): Ticket = Ticket(++generation, UUID.randomUUID().toString()).also {
        current = it; exchanging = false
    }
    @Synchronized fun invalidate() { current = null; exchanging = false }
    @Synchronized fun isCurrent(ticket: Ticket) = current == ticket
    @Synchronized fun accept(ticket: Ticket, callback: String): String? {
        if (current != ticket || exchanging) return null
        val uri = runCatching { URI(callback) }.getOrNull() ?: return null
        if (uri.scheme != "spotiflac" || uri.host != "session-grant" ||
            !uri.path.isNullOrEmpty() || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
        val pairs = runCatching { uri.rawQuery.orEmpty().split('&').map {
            val p = it.split('=', limit = 2)
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
        } }.getOrNull() ?: return null
        if (pairs.count { it.first == "state" } != 1 || pairs.count { it.first == "grant" } != 1) return null
        val data = pairs.toMap()
        if (data["state"] != ticket.state) return null
        val grant = data["grant"]?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return null
        exchanging = true
        return grant
    }
    companion object {
        fun challengeUrl(url: String, ticket: Ticket): String {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && it.substringBefore('=') != "cb" }
            val cb = URLEncoder.encode("spotiflac://session-grant?state=${ticket.state}", "UTF-8")
            return url.substringBefore('#').substringBefore('?') + "?" + (query + "cb=$cb").joinToString("&")
        }
    }
}
