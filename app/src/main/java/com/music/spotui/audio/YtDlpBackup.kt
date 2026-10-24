package com.music.spotui.audio

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import com.music.spotui.data.preferences.PlaybackDiagnostics
import com.music.spotui.data.preferences.getYoutubeCookie
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Search does not depend on InnerTube returning video IDs. Both operations share one bounded native slot. */
internal object YtDlpBackup {
    private val slot = Semaphore(1)
    private val probe = OkHttpClient.Builder().build()
    private var pinnedInstalled = false

    suspend fun lookup(context: Context, known: List<String>, target: BackupSearchPolicy.Target?, quality: AudioQuality,
        foreground: Boolean): YTPlayerUtils.PlaybackData? = BackupCandidatePlanner.lookup(known,
        search = {
            if (target == null || target.title.isBlank() || target.artist.isBlank()) {
                PlaybackDiagnostics.record(context, "Backup search skipped", "No reliable song metadata")
                emptyList()
            } else {
                PlaybackDiagnostics.record(context, "Backup search started", "Independent song search")
                suspend fun search(limit: Int): List<String>? {
                    val request = baseRequest("ytsearch$limit:${BackupSearchPolicy.query(target)}").addOption("--flat-playlist")
                    val raw = execute(context, request, foreground, "Search", 12_000L) ?: return null
                    val report = BackupSearchPolicy.inspect(raw, target)
                    report.rejected.forEach { (reason, count) ->
                        PlaybackDiagnostics.record(context, "Backup matches rejected", "$reason: $count")
                    }
                    PlaybackDiagnostics.record(context, "Backup search result",
                        if (report.ids.isEmpty()) "No accepted matches" else "${report.ids.size} matched candidates")
                    return report.ids
                }
                val first = search(5)
                if (first == null) emptyList() else if (first.isNotEmpty()) first else {
                    PlaybackDiagnostics.record(context, "Backup search expanded", "Checking up to 15 results")
                    search(15).orEmpty()
                }
            }
        }, resolve = { id -> resolve(context, id, quality, foreground) })

    private fun baseRequest(url: String) = YoutubeDLRequest(url).addOption("--ignore-config")
        .addOption("--no-warnings").addOption("--no-progress").addOption("--socket-timeout", "6")
        .addOption("--retries", "0").addOption("--extractor-retries", "0")
        .addOption("--skip-download").addOption("--dump-single-json")

    private fun installPinnedExtractor(context: Context) {
        if (pinnedInstalled) return
        val bytes = context.resources.openRawResource(com.music.spotui.R.raw.ytdlp).use { it.readBytes() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == "b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14")
        val binary = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
        val staged = File(binary.parentFile, "pinned.part")
        staged.writeBytes(bytes)
        check(staged.renameTo(binary))
        pinnedInstalled = true
    }

    private suspend fun execute(context: Context, request: YoutubeDLRequest, foreground: Boolean, purpose: String, budgetMs: Long): String? {
        if (!foreground && !slot.tryAcquire()) {
            PlaybackDiagnostics.record(context, "Backup deferred", "Native lookup already busy")
            return null
        }
        if (foreground) slot.acquire()
        val id = "uspot-backup-${UUID.randomUUID()}"
        var cookies: File? = null
        try {
            PlaybackDiagnostics.record(context, "Backup lookup started", purpose)
            return withTimeoutOrNull(budgetMs) {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.init(context.applicationContext)
                    installPinnedExtractor(context)
                    val authDir = File(context.cacheDir, "backup-auth").apply { mkdirs() }
                    authDir.listFiles().orEmpty().filter { it.name.startsWith("session-") && it.extension == "cookies" }.forEach { it.delete() }
                    val cookie = getYoutubeCookie(context)
                    if (com.music.spotui.data.preferences.isYoutubeLoggedIn(context) && cookie.isNotBlank()) {
                        cookies = File.createTempFile("session-", ".cookies", authDir).apply {
                            setReadable(false, false); setWritable(false, false)
                            setReadable(true, true); setWritable(true, true)
                            writeText(YtDlpStreamPolicy.cookieJar(cookie))
                        }
                        request.addOption("--cookies", cookies!!.absolutePath)
                    }
                    YoutubeDL.execute(request, processId = id).out
                }
            }.also { if (it == null) PlaybackDiagnostics.record(context, "Backup lookup failed", "$purpose deadline reached") }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            PlaybackDiagnostics.record(context, "Backup lookup failed", "$purpose: ${BackupFailurePolicy.describe(error.message)}")
            return null
        } finally {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { YoutubeDL.destroyProcessById(id) }
                    runCatching { cookies?.delete() }
                }
            } finally { slot.release() }
        }
    }

    private suspend fun resolve(context: Context, videoId: String, quality: AudioQuality, foreground: Boolean): YTPlayerUtils.PlaybackData? {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        val lowest = quality == AudioQuality.LOW || (quality == AudioQuality.AUTO && metered)
        val request = baseRequest("https://www.youtube.com/watch?v=$videoId").addOption("--no-playlist")
            .addOption("-f", if (lowest) "worstaudio[protocol=https][ext=m4a]/worstaudio[protocol=https][ext=webm]"
                else "bestaudio[protocol=https][ext=webm]/bestaudio[protocol=https][ext=m4a]")
        val raw = execute(context, request, foreground, "Audio", 14_000L) ?: return null
        val audio = YtDlpStreamPolicy.parse(raw, videoId) ?: run {
            PlaybackDiagnostics.record(context, "Backup candidate failed", "No accepted direct audio")
            return null
        }
        val range = audio.length?.let { "bytes=${it - 1}-${it - 1}" } ?: "bytes=0-16383"
        val probeRequest = Request.Builder().url(audio.url).header("Range", range).apply {
            audio.headers.forEach { (key, value) -> header(key, value) }
        }.build()
        if (!StreamUrlProbe.check(probe.newCall(probeRequest), audio.length)) {
            PlaybackDiagnostics.record(context, "Backup candidate failed", "Stream rejected")
            return null
        }
        StreamRequestHeaders.put(audio.url, audio.headers)
        PlaybackDiagnostics.record(context, "Backup stream ready", if (foreground) "Playback or download" else "Preload")
        return YTPlayerUtils.PlaybackData(null, null, null,
            PlayerResponse.StreamingData.Format(
                itag = 0, url = audio.url, mimeType = "${audio.mime}; codecs=\"${audio.codec}\"", bitrate = audio.bitrate,
                width = null, height = null, contentLength = audio.length, quality = "audio", fps = null,
                qualityLabel = null, averageBitrate = audio.bitrate, audioQuality = null,
                approxDurationMs = audio.durationMs?.toString(), audioSampleRate = null, audioChannels = null,
                loudnessDb = null, lastModified = null, signatureCipher = null, cipher = null, audioTrack = null),
            audio.url, 600)
    }
}
