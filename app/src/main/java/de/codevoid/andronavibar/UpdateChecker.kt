package de.codevoid.andronavibar

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val RELEASE_URL =
        "https://api.github.com/repos/c0dev0id/aR2Launcher/releases/tags/dev"

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentJob: Job? = null

    /**
     * Run the full check → download → install flow, reporting state changes
     * through [onStatus] (button label) and [onProgress] (0–1 fill).
     *
     * Phases:
     *   "Checking…"
     *   → "Already on <hash>" (3 s) → "Check for Update"
     *   → "Downloading <hash>…" (with progress) → install → "Check for Update"
     *   → "Check for Update"  (on any error, silently)
     *
     * Any previous in-flight check is cancelled before a new one starts.
     */
    fun check(
        activity: Activity,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        currentJob?.cancel()
        onStatus("Checking\u2026")

        currentJob = scope.launch {
            val result = withContext(Dispatchers.IO) { fetchRelease() }
            if (activity.isFinishing) return@launch

            if (result == null) {
                onStatus("Check for Update")
                return@launch
            }

            val (remoteSha, apkUrl, apkName) = result

            if (remoteSha == BuildConfig.GIT_SHA) {
                onStatus("Already on $remoteSha")
                delay(3_000)
                if (!activity.isFinishing) onStatus("Check for Update")
                return@launch
            }

            onStatus("Downloading $remoteSha\u2026")
            val file = withContext(Dispatchers.IO) { downloadApk(activity, apkUrl, apkName, onProgress) }
            onProgress(0f)
            if (activity.isFinishing) return@launch

            if (file == null) {
                onStatus("Check for Update")
                return@launch
            }

            onStatus("Check for Update")

            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun fetchRelease(): Triple<String, String, String>? = try {
        val conn = URL(RELEASE_URL).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            if (assets.length() == 0) null
            else {
                val asset = assets.getJSONObject(0)
                val apkName = asset.getString("name")
                val apkUrl = asset.getString("browser_download_url")
                val sha = apkName.removeSuffix(".apk").substringAfterLast("-")
                Triple(sha, apkUrl, apkName)
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) { null }

    private fun downloadApk(
        activity: Activity,
        url: String,
        name: String,
        onProgress: (Float) -> Unit
    ): File? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            val file = File(activity.cacheDir, name)
            val totalBytes = conn.contentLengthLong
            var bytesRead = 0L
            val handler = Handler(Looper.getMainLooper())
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        output.write(buffer, 0, n)
                        bytesRead += n
                        if (totalBytes > 0) {
                            val progress = bytesRead.toFloat() / totalBytes
                            handler.post { onProgress(progress) }
                        }
                    }
                }
            }
            file
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) { null }
}
