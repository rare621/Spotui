package com.music.spotui.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticSongTest {
    @Test fun readableSongAndArtistIncludeUnicode() {
        assertEquals("Creepin’ — Metro Boomin", DiagnosticSongLabel.create(" Creepin’ ", "Metro Boomin"))
        assertEquals("Beyoncé — Artist", DiagnosticSongLabel.create("Beyoncé", "Artist"))
    }
    @Test fun metadataCannotInjectLogLinesUrlsOrTokens() {
        val safe=DiagnosticSongLabel.create("Song\nHTTP https://example.test/?secret=yes", "Artist\rCookie=secret")
        assertFalse(safe.contains("\n")); assertFalse(safe.contains("\r"))
        assertFalse(safe.contains("https://")); assertFalse(safe.contains("secret"))
        assertTrue(safe.length<=220)
        assertEquals(220, DiagnosticSongLabel.create("x".repeat(1000), "artist").length)
    }
    @Test fun concurrentLookupsCannotRenameEachOthersEvents() = runTest {
        val current=async(PlaybackDiagnosticSong.context("Current — Artist A")) {
            delay(10); assertEquals("Current — Artist A",PlaybackDiagnosticSong.current())
            withContext(Dispatchers.Default) { assertEquals("Current — Artist A",PlaybackDiagnosticSong.current()) }
        }
        val preload=async(PlaybackDiagnosticSong.context("Next — Artist B")) {
            delay(5); assertEquals("Next — Artist B",PlaybackDiagnosticSong.current())
            withContext(Dispatchers.Default) { assertEquals("Next — Artist B",PlaybackDiagnosticSong.current()) }
        }
        awaitAll(current,preload)
        assertEquals("",PlaybackDiagnosticSong.current())
    }
    @Test fun explicitCallbackCaptureKeepsItsSongAfterCoroutineFinishes() = runTest {
        val captured=withContext(PlaybackDiagnosticSong.context("Next song")) { PlaybackDiagnosticSong.current() }
        assertEquals("Next song",captured);assertEquals("",PlaybackDiagnosticSong.current())
    }
}
