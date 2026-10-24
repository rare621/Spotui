package com.music.spotui.data.backup

/** Only the regular USpot app and its private preview share a backup family. */
object BackupIdentityPolicy {
    private val family = setOf("com.music.spotui.modified.streampreview", "com.music.spotui.modified.polishpreview", "com.music.spotui.modified", "com.music.spotui.modified.webpreview", "com.music.spotui.modified.desktoppreview")
    fun compatible(source: String, target: String): Boolean =
        source.isNotBlank() && (source == target || source in family && target in family)
}
