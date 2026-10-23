package com.music.spotui.cache

import android.content.Context
import java.io.File

object CacheStorage {
    data class Usage(val audio: Long, val artwork: Long, val lossless: Long) {
        val total get() = audio + artwork + lossless
    }

    fun usage(context: Context) = Usage(
        size(File(context.cacheDir, "media")),
        size(File(context.cacheDir, "image_manager_disk_cache")),
        size(File(context.cacheDir, "slsk")),
    )

    private fun size(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        val root = dir.canonicalPath + File.separator
        dir.walkTopDown().onEnter { it == dir || it.canonicalPath.startsWith(root) }.filter { it.isFile && it.canonicalPath.startsWith(root) }.sumOf { it.length() }
    }.getOrDefault(0L)
}
