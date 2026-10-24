package com.music.spotui.setup

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class VerificationAttemptTest {
    private fun callback(t: VerificationAttempt.Ticket, grant: String = "once") = "spotiflac://session-grant?state=${t.state}&grant=$grant"
    @Test fun retryInvalidatesOldPageAndAcceptsNewPage() {
        val guard = VerificationAttempt(); val old = guard.begin(); val fresh = guard.begin()
        assertNull(guard.accept(old, callback(old)))
        assertNull(guard.accept(fresh, callback(old)))
        assertEquals("once", guard.accept(fresh, callback(fresh)))
    }
    @Test fun grantIsExchangedAtMostOnce() {
        val guard = VerificationAttempt(); val t = guard.begin()
        assertEquals("once", guard.accept(t, callback(t)))
        assertNull(guard.accept(t, callback(t)))
    }
    @Test fun leavingInvalidatesPendingResponse() {
        val guard = VerificationAttempt(); val t = guard.begin(); guard.accept(t, callback(t)); guard.invalidate()
        assertFalse(guard.isCurrent(t)); assertNull(guard.accept(t, callback(t)))
    }
    @Test fun rejectsUntrustedOrAmbiguousCallbacks() {
        listOf("https://session-grant", "spotiflac://wrong-host", "spotiflac://session-grant/path", "spotiflac://user@session-grant", "spotiflac://session-grant:80").forEach { prefix ->
            val guard = VerificationAttempt(); val t = guard.begin()
            assertNull(guard.accept(t, "$prefix?state=${t.state}&grant=once"))
        }
        val guard = VerificationAttempt(); val t = guard.begin()
        assertNull(guard.accept(t, callback(t) + "&grant=twice"))
        assertNull(guard.accept(t, callback(t) + "&state=${t.state}"))
        assertNull(guard.accept(t, callback(t) + "#fragment"))
        assertNull(guard.accept(t, callback(t, "")))
        assertNull(guard.accept(t, callback(t, "%broken")))
        assertEquals("once", guard.accept(t, callback(t)))
    }
    @Test fun challengePreservesParametersAndReplacesCallback() {
        val t = VerificationAttempt().begin()
        for (url in listOf("https://example.test/verify", "https://example.test/verify?token=a%2Bb&cb=old")) {
            val result = URI(VerificationAttempt.challengeUrl(url, t))
            val pairs = result.rawQuery.split('&')
            assertEquals(1, pairs.count { it.startsWith("cb=") })
            assertEquals("spotiflac://session-grant?state=${t.state}", URLDecoder.decode(pairs.last().substringAfter('='), "UTF-8"))
            if (url.contains("token")) assertTrue(pairs.contains("token=a%2Bb"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInsecureChallenge() {
        VerificationAttempt.challengeUrl("http://example.test/verify", VerificationAttempt().begin())
    }
}
