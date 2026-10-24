package com.music.spotui.audio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YtDlpStreamPolicyTest {
    private val id = "Abcdef123_-"
    private fun valid() = JSONObject().put("id", id).put("url", "https://rr1.googlevideo.com/videoplayback?expire=9999999999")
        .put("ext", "webm").put("protocol", "https").put("acodec", "opus").put("vcodec", "none")
        .put("duration", 184.25).put("abr", 145.0).put("filesize", 12000)
        .put("http_headers", JSONObject().put("User-Agent", "matching client").put("Cookie", "secret"))
    @Test fun exactAudioAndClientHeadersAreKept() {
        val result = YtDlpStreamPolicy.parse(valid().toString(), id)!!
        assertEquals("audio/webm", result.mime); assertEquals(145000, result.bitrate)
        assertEquals(184250L, result.durationMs); assertEquals(12000L, result.length)
        assertEquals(mapOf("User-Agent" to "matching client"), result.headers)
    }
    @Test fun rejectsWrongSongVideoPlaylistAndLive() {
        assertNull(YtDlpStreamPolicy.parse(valid().put("id", "other").toString(), id))
        assertNull(YtDlpStreamPolicy.parse(valid().put("vcodec", "avc1").toString(), id))
        assertNull(YtDlpStreamPolicy.parse(valid().put("entries", org.json.JSONArray()).toString(), id))
        assertNull(YtDlpStreamPolicy.parse(valid().put("is_live", true).toString(), id))
    }
    @Test fun rejectsManifestsUntrustedHostsAndExpiredLinks() {
        for (url in listOf("http://rr1.googlevideo.com/a", "https://googlevideo.com.evil.test/a", "file:///tmp/song", "https://user@rr1.googlevideo.com/a", "https://rr1.googlevideo.com:444/a", "https://rr1.googlevideo.com/a?expire=10")) {
            assertNull(YtDlpStreamPolicy.parse(valid().put("url", url).toString(), id, 100))
        }
        assertNull(YtDlpStreamPolicy.parse(valid().put("protocol", "m3u8_native").toString(), id))
        assertNull(YtDlpStreamPolicy.parse(valid().put("ext", "mp4").toString(), id))
    }
    @Test fun cookiesAreScopedAndMalformedLinesDropped() {
        val jar = YtDlpStreamPolicy.cookieJar("SID=ok; SAPISID=abc=def; broken; BAD=bad\nline")
        assertTrue(jar.contains(".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tok\n"))
        assertTrue(jar.contains("SAPISID\tabc=def")); assertFalse(jar.contains("broken")); assertFalse(jar.contains("BAD"))
    }
    @Test fun headersNeverForwardCredentialsOrInjectedLines() {
        assertEquals(mapOf("Origin" to "https://www.youtube.com"), YtDlpStreamPolicy.safeHeaders(mapOf(
            "Authorization" to "secret", "Cookie" to "secret", "Origin" to "https://www.youtube.com", "User-Agent" to "bad\r\nInjected: yes")))
    }
    @Test fun headersHaveBoundedLifetimeAndCapacity() {
        StreamRequestHeaders.put("ttl", mapOf("User-Agent" to "a"), 1)
        assertNotNull(StreamRequestHeaders.get("ttl", 2))
        assertNull(StreamRequestHeaders.get("ttl", 31L * 60 * 1000000000))
        repeat(65) { StreamRequestHeaders.put("capacity$it", emptyMap(), 1) }
        assertNull(StreamRequestHeaders.get("capacity0", 2)); assertNotNull(StreamRequestHeaders.get("capacity64", 2))
    }
}
