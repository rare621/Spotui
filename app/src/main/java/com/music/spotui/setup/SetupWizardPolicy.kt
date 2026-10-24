package com.music.spotui.setup

/** Navigation is explicit; completed credentials must not skip the user's source choice. */
object SetupWizardPolicy {
    fun afterSpotify() = 1
    fun afterSourceLogin() = 1
    fun afterLossless() = 3
    fun back(page: Int) = (page - 1).coerceAtLeast(0)
    fun resumePage(saved: Int, spotify: Boolean, source: Boolean, lossless: Boolean): Int {
        val allowed = when { !spotify -> 0; !source -> 1; !lossless -> 2; else -> 3 }
        return saved.coerceIn(0, allowed)
    }
    fun canFinish(spotify: Boolean, source: Boolean, lossless: Boolean) = spotify && source && lossless
}
