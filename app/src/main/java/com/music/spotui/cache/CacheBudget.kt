package com.music.spotui.cache

import java.io.File

/** Reusable media only. Never includes downloads, preferences, stats, or Home data. */
object CacheBudget {
    const val MIB = 1024L * 1024L
    const val AUDIO_BYTES = 128L * MIB
    const val ARTWORK_BYTES = 64L * MIB
    const val LOSSLESS_BYTES = 64L * MIB
    const val TOTAL_BYTES = AUDIO_BYTES + ARTWORK_BYTES + LOSSLESS_BYTES

    // Only completed direct-child FLAC cache files belong to this trimmer.
    private fun files(root: File): List<File> = root.listFiles().orEmpty().filter {
        it.isFile && it.extension == "flac" && it.canonicalFile.parentFile == root.canonicalFile
    }

    fun losslessBytes(root: File): Long = files(root).sumOf { it.length() }

    fun pruneInstalledUpdates(root: File, installedBuild: Long, now: Long) {
        val name = Regex("USpot-([0-9]+)\\.apk")
        root.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == root.canonicalFile }.forEach { file ->
            val build = name.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if ((build != null && build <= installedBuild) ||
                (file.name == "update.part" && now - file.lastModified() > 86_400_000L)) file.delete()
        }
    }

    fun trimLossless(root: File, limit: Long, protectedNames: Set<String>, keepAfter: Long): Long {
        require(limit >= 0)
        var bytes = losslessBytes(root)
        files(root).sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).forEach { file ->
            if (bytes > limit && file.name !in protectedNames && file.lastModified() < keepAfter) {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
        return bytes
    }
}
