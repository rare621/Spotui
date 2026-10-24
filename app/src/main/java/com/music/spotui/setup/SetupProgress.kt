package com.music.spotui.setup

import android.content.Context
import java.time.Instant

object SetupProgress {
    private fun prefs(c: Context) = c.getSharedPreferences("uspot_setup", 0)
    fun verified(c: Context, name: String) = prefs(c).getBoolean(name, false)
    fun mark(c: Context, name: String, ok: Boolean) {
        val edit = prefs(c).edit().putBoolean(name, ok)
        if (name == "spotify" || name == "source" || name == "lossless") edit.putBoolean("audio", false).putBoolean("complete", false)
        edit.apply()
    }
    fun begin(c: Context) {
        if (prefs(c).getInt("setup_schema", 0) != 3) prefs(c).edit()
            .putInt("setup_schema", 3).putInt("page", 0)
            .putBoolean("audio", false).putBoolean("complete", false).apply()
    }
    fun choice(c: Context): String? = prefs(c).getString("source_choice", null)
    fun choose(c: Context, choice: String) { prefs(c).edit().putString("source_choice", choice).apply() }
    fun page(c: Context) = prefs(c).getInt("page", 0)
    fun setPage(c: Context, page: Int) { prefs(c).edit().putInt("page", page.coerceIn(0, 3)).apply() }
    fun completed(c: Context) = prefs(c).getInt("setup_schema", 0) == 3 && verified(c, "complete")
    fun restart(c: Context) { prefs(c).edit().putInt("page", 0).putBoolean("spotify", false).putBoolean("source", false)
        .putBoolean("lossless", false).putBoolean("audio", false).putBoolean("complete", false).apply() }
    fun finish(c: Context) { prefs(c).edit().putBoolean("complete", true).apply() }
    fun sessionCurrent(expires: String, now: Instant = Instant.now()): Boolean =
        expires.isBlank() || runCatching { Instant.parse(expires).isAfter(now) }.getOrDefault(false)
    fun installId(c: Context): String {
        val p = prefs(c)
        return p.getString("spotiflac_install", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").also {
            p.edit().putString("spotiflac_install", it).apply()
        }
    }
}
