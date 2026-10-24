package com.music.spotui.setup

/** Connectivity failures are inconclusive, never a reason to erase credentials. */
object StartupCheckPolicy {
    enum class Connection { MISSING, CONFIRMED, UNCONFIRMED, OFFLINE }
    fun shouldCheck(now: Long, last: Long, build: Int, lastBuild: Int): Boolean =
        last == 0L || build != lastBuild || now < last || now - last >= 60_000L
    fun connection(credentialPresent: Boolean, online: Boolean, confirmed: Boolean): Connection = when {
        !credentialPresent -> Connection.MISSING
        !online -> Connection.OFFLINE
        confirmed -> Connection.CONFIRMED
        else -> Connection.UNCONFIRMED
    }
    fun needsSignIn(connection: Connection): Boolean = connection == Connection.MISSING
}
