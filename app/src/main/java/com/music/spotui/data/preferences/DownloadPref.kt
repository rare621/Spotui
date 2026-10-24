package com.music.spotui.data.preferences

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject
import java.io.File

/**
 * Tracks downloaded (offline) tracks. Each entry is keyed by the song id and stores
 * the full [SongsModel] plus the local file path the audio was saved to, so the
 * Downloads screen can list them offline and [com.music.spotui.di.SongPlayer]
 * can play the local file instead of re-resolving + streaming from YouTube.
 */
private const val PREF = "Downloads"

private fun SongsModel.toJson(filePath: String): String = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("album", album)
    put("singer", singer)
    put("coverUri", coverUri)
    put("url", url)
    put("spotifyTrackId", spotifyTrackId)
    put("explicit", explicit)
    put("durationMs", durationMs)
    put("filePath", filePath)
}.toString()

private fun parse(json: String): Pair<SongsModel, String>? = runCatching {
    val o = JSONObject(json)
    SongsModel(
        id = o.getInt("id"),
        title = o.getString("title"),
        album = o.optString("album"),
        singer = o.getString("singer"),
        coverUri = o.optString("coverUri"),
        url = o.getString("url"),
        spotifyTrackId = o.optString("spotifyTrackId"),
        explicit = o.optBoolean("explicit", false),
        durationMs = o.optInt("durationMs", 0),
    ) to o.optString("filePath")
}.getOrNull()

// query → file path, rebuilt after any change: downloadedPathForQuery runs on every play.
@Volatile private var pathByQuery: Map<String, String>? = null

fun invalidateDownloadCache() { pathByQuery = null }

fun addDownload(context: Context, song: SongsModel, filePath: String) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putString(song.id.toString(), song.toJson(filePath))
        .apply()
    pathByQuery = null
}

fun removeDownload(context: Context, songId: String) {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    prefs.getString(songId, null)?.let { json ->
        parse(json)?.second?.let { path -> runCatching { File(path).delete() } }
    }
    prefs.edit().remove(songId).apply()
    pathByQuery = null
}

fun isDownloaded(context: Context, songId: String): Boolean =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains(songId)

fun getDownloadedSongs(context: Context): List<SongsModel> {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    return prefs.all.values.mapNotNull { (it as? String)?.let(::parse)?.first }
}

/** Local file path for a track query (SongsModel.url), if downloaded and present on disk. */
fun downloadedPathForQuery(context: Context, query: String): String? {
    val index = pathByQuery ?: getDownloadedEntries(context)
        .filter { it.second.isNotBlank() }
        .associate { (song, path) -> song.url to path }
        .also { pathByQuery = it }
    return index[query]?.takeIf { File(it).exists() }
}

/** Every downloaded track paired with its on-disk file path. */
fun getDownloadedEntries(context: Context): List<Pair<SongsModel, String>> =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .all.values.mapNotNull { (it as? String)?.let(::parse) }

/** Delete every downloaded file and forget all download entries. Returns count removed. */
fun clearAllDownloads(context: Context): Int {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val entries = getDownloadedEntries(context)
    entries.forEach { (_, path) -> if (path.isNotBlank()) runCatching { File(path).delete() } }
    prefs.edit().clear().apply()
    pathByQuery = null
    return entries.size
}

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(120).ifBlank { "track" }

/**
 * Copy every downloaded track out of the app's private storage into the shared
 * **Music/spotui** folder as `Artist - Title.<ext>`, so files show up in normal
 * file managers / music apps (no root needed). Uses MediaStore on API 29+.
 * Returns (exportedCount, destinationLabel).
 */
fun exportDownloads(context: Context): Pair<Int, String> {
    val entries = getDownloadedEntries(context).filter { it.second.isNotBlank() && File(it.second).exists() }
    if (entries.isEmpty()) return 0 to "No downloaded files to export"
    val count = entries.count { (song, path) -> exportFile(context, song, path) }
    return count to "Music/spotui"
}

/** Export a single downloaded track to public Music/spotui. Returns true on success. */
fun exportDownload(context: Context, song: SongsModel): Boolean {
    val path = getDownloadedEntries(context).firstOrNull { it.first.id == song.id }?.second
        ?.takeIf { it.isNotBlank() && File(it).exists() } ?: return false
    return exportFile(context, song, path)
}

/** Copy one private download file into shared Music/spotui as "Artist - Title.<ext>". */
private fun exportFile(context: Context, song: SongsModel, path: String): Boolean = runCatching {
    val src = File(path)
    if (!src.exists()) return false
    val ext = src.extension.ifBlank { "flac" }
    val mime = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "webm" -> "audio/webm"
        else -> "audio/*"
    }
    val displayName = "${sanitizeFileName("${song.singer} - ${song.title}")}.$ext"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/spotui")
            // Hidden from other apps until the copy finishes.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        // Already exported: a second insert would add an "Artist - Title (1)" copy.
        val exported = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(displayName, "${Environment.DIRECTORY_MUSIC}/spotui/"), null,
        )?.use { it.count > 0 } == true
        if (exported) return true
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val copied = runCatching {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        }.isSuccess
        if (!copied) {
            // Never leave a half-written track in the user's music library.
            resolver.delete(uri, null, null)
            return false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "spotui",
        ).apply { mkdirs() }
        val dest = File(dir, displayName)
        runCatching { src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } } }
            .onFailure { dest.delete(); return false }
    }
    true
}.getOrDefault(false)

/** Bounded retry records survive process death; cancelled tasks are not failures. */
object FailedDownloads {
    data class Entry(val song: SongsModel, val reason: String)
    private fun prefs(c:Context)=c.getSharedPreferences("download_failures",0)
    fun entries(c:Context):List<Entry> = prefs(c).all.values.mapNotNull { raw -> runCatching {
        val o=JSONObject(raw as String)
        parse(o.getString("song"))?.first?.let { Entry(it,o.optString("reason","Couldn't finish downloading")) }
    }.getOrNull() }
    @Synchronized fun record(c:Context,song:SongsModel) {
        val p=prefs(c);val editor=p.edit()
        if(p.all.size>=50 && !p.contains(song.id.toString()))p.all.keys.firstOrNull()?.let{editor.remove(it)}
        editor.putString(song.id.toString(),JSONObject().put("song",song.toJson("")).put("reason","Couldn't finish downloading. Check your connection and try again.").toString()).apply()
    }
    fun dismiss(c:Context,id:Int){prefs(c).edit().remove(id.toString()).apply()}
}
