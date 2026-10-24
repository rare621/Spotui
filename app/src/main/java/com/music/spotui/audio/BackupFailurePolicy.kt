package com.music.spotui.audio

/** Fixed diagnostic categories only. Never include extractor stderr, account data or URLs. */
object BackupFailurePolicy {
    fun describe(message: String?): String {
        val text = message.orEmpty().lowercase()
        return when {
            "403" in text -> "HTTP 403"
            "429" in text || "too many requests" in text -> "Provider rate limit"
            "sign in" in text || "login_required" in text || "cookies" in text -> "YouTube sign-in required or rejected"
            "javascript" in text || "challenge" in text || "nsig" in text -> "YouTube JavaScript challenge"
            "requested format" in text || "no video formats" in text -> "No direct audio format"
            "not available" in text || "unplayable" in text || "unavailable" in text -> "Video unavailable"
            "timed out" in text || "timeout" in text -> "Network timeout"
            "name resolution" in text || "resolve host" in text -> "DNS failure"
            else -> "Extractor or runtime failure"
        }
    }
}
