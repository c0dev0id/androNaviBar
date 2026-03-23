package de.codevoid.andronavibar

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val RELEASE_URL =
        "https://api.github.com/repos/c0dev0id/aR2Launcher/releases/tags/dev"

    fun check(activity: Activity) {
        Toast.makeText(activity, "Checking for updates\u2026", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { fetchRelease() }

            if (result == null) {
                Toast.makeText(activity, "Failed to check for updates", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val (remoteSha, apkUrl, apkName) = result

            if (remoteSha == BuildConfig.GIT_SHA) {
                Toast.makeText(activity, "Already up to date ($remoteSha)", Toast.LENGTH_SHORT).show()
                return@launch
            }

            AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Current: ${BuildConfig.GIT_SHA}\nLatest:  $remoteSha\n\nDownload and install?")
                .setPositiveButton("Install") { _, _ -> downloadAndInstall(activity, apkUrl, apkName) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun fetchRelease(): Triple<String, String, String>? = try {
        val conn = URL(RELEASE_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        conn.disconnect()

        val assets = json.getJSONArray("assets")
        if (assets.length() == 0) null
        else {
            val asset = assets.getJSONObject(0)
            val apkName = asset.getString("name")
            val apkUrl = asset.getString("browser_download_url")
            // Extract SHA from asset name: aR2Launcher-dev-XXXXXXX.apk
            val sha = apkName.removeSuffix(".apk").substringAfterLast("-")
            Triple(sha, apkUrl, apkName)
        }
    } catch (_: Exception) { null }

    private fun downloadAndInstall(activity: Activity, url: String, name: String) {
        Toast.makeText(activity, "Downloading\u2026", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val file = withContext(Dispatchers.IO) { downloadApk(activity, url, name) }

            if (file == null) {
                Toast.makeText(activity, "Download failed", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }
    }

    private fun downloadApk(activity: Activity, url: String, name: String): File? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        val file = File(activity.cacheDir, name)
        conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        conn.disconnect()
        file
    } catch (_: Exception) { null }
}
