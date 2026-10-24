package com.music.spotui.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.music.spotui.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

object AppUpdates {
    data class State(val checking: Boolean = false, val checked: Boolean = false, val release: AppRelease? = null,
        val error: String = "", val showPrompt: Boolean = false, val downloading: Boolean = false, val progress: Int = 0)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val checkLock = Mutex()
    private var attemptedThisProcess = false
    private var downloadJob: Job? = null
    private var readyFile: File? = null
    @Volatile private var activeDownload: okhttp3.Call? = null
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS).followSslRedirects(false).build()
    private val checkClient = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private fun prefs(c: Context) = c.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    suspend fun check(context: Context, manual: Boolean = false) {
        if (BuildConfig.IS_PREVIEW) {
            mutable.update { it.copy(checked = true, error = if (manual) "You are testing a preview. Normal releases stay in your regular USpot app." else "") }
            return
        }
        if (!checkLock.tryLock()) return
        val c = context.applicationContext
        try {
            val p = prefs(c); val now = System.currentTimeMillis()
            if (mutable.value.downloading) return
            if (!manual && !UpdateCheckPolicy.shouldCheck(!attemptedThisProcess, now, p.getLong("last_auto", 0L))) return
            if (now < p.getLong("retry_at", 0L)) {
                if (manual) mutable.update { it.copy(error = "GitHub asked us to wait before checking again. Please try later.") }
                return
            }
            attemptedThisProcess = true
            // Failed requests also get a session throttle; a fresh process checks again.
            p.edit().putLong("last_auto", now).apply()
            mutable.update { it.copy(checking = true, error = "") }
            val release = withContext(Dispatchers.IO) {
                withTimeout(15_000L) {
                    val request = Request.Builder().url(UpdatePolicy.API).header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "USpot/${BuildConfig.VERSION_NAME} build-${BuildConfig.VERSION_CODE}")
                        .apply { p.getString("etag", null)?.let { header("If-None-Match", it) } }.build()
                    val call = checkClient.newCall(request)
                    val handle = currentCoroutineContext().job.invokeOnCompletion { if (it is CancellationException) call.cancel() }
                    try {
                        call.execute().use { response ->
                            if (response.code == 403 || response.code == 429) {
                                val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                                val retry = response.header("Retry-After")?.toLongOrNull()?.times(1000)?.plus(now)
                                p.edit().putLong("retry_at", (retry ?: reset ?: (now + 15 * 60_000L)).coerceIn(now + 60_000L, now + 24 * 60 * 60_000L)).apply()
                                error("GitHub update checks are temporarily unavailable. Please try later.")
                            }
                            val raw = if (response.code == 304) p.getString("release_json", null)
                                ?: error("Check again to refresh release information.") else {
                                kotlin.check(response.isSuccessful) { "Could not check GitHub releases (HTTP ${response.code})." }
                                val body = response.body ?: error("Empty GitHub response.")
                                require(!body.source().request(256 * 1024L + 1)) { "Release response is too large." }
                                body.string()
                            }
                            val parsed = UpdatePolicy.parse(raw)
                            if (response.code != 304) p.edit().putString("release_json", raw)
                                .putString("etag", response.header("ETag")).apply()
                            parsed
                        }
                    } finally { handle.dispose(); call.cancel() }
                }
            }
            val available = release?.takeIf { it.code > BuildConfig.VERSION_CODE.toLong() }
            p.edit().putLong("last_auto", now).putLong("retry_at", 0L).apply()
            mutable.update { it.copy(checked = true, release = available, error = "",
                showPrompt = available != null && (manual || p.getLong("dismissed", 0L) != available.code)) }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            mutable.update { it.copy(error = "The update check took too long. Check your connection and try again.") }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(error = error.message?.take(180) ?: "Could not check for updates. Check your connection.") }
        } finally { mutable.update { it.copy(checking = false) }; checkLock.unlock() }
    }

    fun showAvailable() { if (mutable.value.release != null) mutable.update { it.copy(showPrompt = true, error = "") } }
    fun cancel(context: Context) {
        mutable.value.release?.let { prefs(context).edit().putLong("dismissed", it.code).apply() }
        activeDownload?.cancel()
        downloadJob?.cancel()
        mutable.update { it.copy(showPrompt = false, error = "") }
    }

    suspend fun download(context: Context): Boolean {
        if (mutable.value.downloading) return false
        val c = context.applicationContext
        val release = mutable.value.release ?: return false
        readyFile?.takeIf { it.isFile && it.name == "USpot-${release.code}.apk" }?.let { return true }
        downloadJob = currentCoroutineContext().job
        mutable.update { it.copy(downloading = true, progress = 0, error = "") }
        var partial: File? = null
        try {
            val file = withContext(Dispatchers.IO) {
                withTimeout(150_000L) {
                    val dir = File(c.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val part = File(dir, "update.part"); partial = part
                    val call = client.newCall(Request.Builder().url(release.apk).header("User-Agent", "USpot updater").build())
                    activeDownload = call
                    val handle = currentCoroutineContext().job.invokeOnCompletion { if (it is CancellationException) call.cancel() }
                    try {
                        call.execute().use { response ->
                            kotlin.check(response.isSuccessful) { "Update download failed (HTTP ${response.code})." }
                            val body = response.body ?: error("Empty update download.")
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L; var lastProgress = -1
                            body.byteStream().use { input -> part.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer); if (n < 0) break
                                    total += n; require(total <= release.bytes) { "The APK size differs from its release." }
                                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                                    val progress = (total * 100 / release.bytes).toInt()
                                    if (progress != lastProgress) { lastProgress = progress; mutable.update { it.copy(progress = progress) } }
                                }
                            } }
                            require(total == release.bytes && digest.digest().hex() == release.sha256) { "The update checksum did not match. Please try again." }
                        }
                    } finally { handle.dispose(); call.cancel() }
                    val target = File(dir, "USpot-${release.code}.apk")
                    kotlin.check(part.renameTo(target)) { "Cannot prepare the update file." }
                    try { verifyApk(c, target, release.code) } catch (e: Exception) { target.delete(); throw e }
                    target
                }
            }
            readyFile = file
            return true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message?.take(180) ?: "Could not download the update.") }
            return false
        } finally {
            partial?.delete(); activeDownload = null; downloadJob = null
            mutable.update { it.copy(downloading = false) }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, expectedCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("The download is not an Android APK.")
        fun code(p: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        fun signers(p: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
            return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        }
        require(UpdatePolicy.compatible(context.packageName, archive.packageName, code(installed), code(archive), expectedCode, signers(installed), signers(archive))) {
            "This APK cannot update the installed USpot: its package, build or signing key differs. Keep your app installed."
        }
    }

    fun openInstaller(context: Context) {
        try {
            kotlin.check(context.packageManager.canRequestPackageInstalls()) { "Allow updates from USpot in Android's install-permission settings, then tap Install again." }
            val file = readyFile?.takeIf { it.isFile } ?: error("Download the update again before installing.")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            mutable.update { it.copy(showPrompt = false, error = "") }
        } catch (e: Exception) { mutable.update { it.copy(showPrompt = true, error = e.message?.take(180) ?: "Android could not open the installer.") } }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
