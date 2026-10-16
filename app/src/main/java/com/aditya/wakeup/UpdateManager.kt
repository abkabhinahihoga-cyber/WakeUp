package com.aditya.wakeup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeBytes: Long = 0L,
    val sha256: String? = null
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class NotConfigured(val message: String) : UpdateState
    data class Available(val info: UpdateInfo, val currentCode: Int, val currentName: String) : UpdateState
    data class UpToDate(val currentCode: Int, val currentName: String) : UpdateState
    data class Downloading(val progressPercent: Int) : UpdateState
    data class ReadyToInstall(val apkFile: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

class UpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "WakeUpUpdateManager"
        const val PLACEHOLDER_URL_HOST = "raw.githubusercontent.com/aditya/wakeup-alarm"

        val SAMPLE_MANIFEST_JSON = """
            {
              "versionCode": 7,
              "versionName": "1.6.0",
              "releaseNotes": "• Minimal home screen redesign\n• Centralized default ringtone library\n• 7 theme options\n• SHA-256 update integrity verification",
              "downloadUrl": "https://example.com/WakeUp-1.6.0-debug.apk",
              "apkSizeBytes": 15000000,
              "sha256": "4a72d3e18f26b52e691234567890abcdef1234567890abcdef1234567890abcdef"
            }
        """.trimIndent()

        fun isUpdateAvailable(currentCode: Int, remoteCode: Int): Boolean = remoteCode > currentCode

        fun calculateSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun evaluateUpdate(
            currentCode: Int,
            currentName: String,
            remoteCode: Int,
            remoteName: String,
            notes: String,
            downloadUrl: String,
            size: Long,
            sha256: String? = null
        ): UpdateState {
            if (remoteCode <= 0 || downloadUrl.isBlank()) {
                return UpdateState.Error("Invalid update manifest received from server: missing versionCode or downloadUrl.")
            }

            return if (isUpdateAvailable(currentCode, remoteCode)) {
                val info = UpdateInfo(remoteCode, remoteName, notes, downloadUrl, size, sha256)
                UpdateState.Available(info, currentCode, currentName)
            } else {
                UpdateState.UpToDate(currentCode, currentName)
            }
        }

        fun parseManifest(rawJson: String, currentCode: Int, currentName: String): UpdateState {
            return runCatching {
                val json = JSONObject(rawJson)
                val remoteCode = json.optInt("versionCode", 0)
                val remoteName = json.optString("versionName", "")
                val notes = json.optString("releaseNotes", "New performance and feature updates.")
                val downloadUrl = json.optString("downloadUrl", "")
                val size = json.optLong("apkSizeBytes", 0L)
                val sha256 = json.optString("sha256", "").takeIf { it.isNotBlank() }
                evaluateUpdate(currentCode, currentName, remoteCode, remoteName, notes, downloadUrl, size, sha256)
            }.getOrElse {
                // Fallback for JVM unit tests where android.jar's org.json.JSONObject is not mocked
                val remoteCode = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(rawJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val remoteName = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1) ?: ""
                val notes = Regex("\"releaseNotes\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1) ?: "New performance and feature updates."
                val downloadUrl = Regex("\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1) ?: ""
                val size = Regex("\"apkSizeBytes\"\\s*:\\s*(\\d+)").find(rawJson)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val sha256 = Regex("\"sha256\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                evaluateUpdate(currentCode, currentName, remoteCode, remoteName, notes, downloadUrl, size, sha256)
            }
        }

        /**
         * Connects to a URL following up to [maxRedirects] redirects (HTTP 301, 302, 303, 307, 308).
         * Necessary for GitHub Releases and cloud CDNs which issue 302/307 redirects to storage blobs.
         * Enforces HTTPS downgrade protection: once starting on HTTPS, will not follow redirects to insecure HTTP.
         */
        fun openConnectionWithRedirects(urlString: String, maxRedirects: Int = 5): HttpURLConnection {
            var currentUrl = urlString
            val initialIsHttps = urlString.startsWith("https://", ignoreCase = true)
            var redirects = 0
            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 25000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "WakeUp-Android")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) {
                        throw IllegalStateException("HTTP redirect received ($code) with no Location header.")
                    }
                    val nextUrl = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    if (initialIsHttps && nextUrl.startsWith("http://", ignoreCase = true)) {
                        throw SecurityException("Insecure HTTP redirect downgrade rejected from HTTPS to HTTP ($nextUrl).")
                    }
                    currentUrl = nextUrl
                    redirects++
                } else {
                    return conn
                }
            }
            throw IllegalStateException("Too many HTTP redirects ($redirects) while connecting to $urlString")
        }
    }

    fun getCurrentVersion(): Pair<Int, String> {
        val pInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            pInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode
        }
        val name = pInfo.versionName ?: "1.6.0"
        return code to name
    }

    suspend fun checkForUpdates(serverUrl: String): UpdateState = withContext(Dispatchers.IO) {
        val trimmed = serverUrl.trim()
        if (trimmed.isBlank() || trimmed.contains(PLACEHOLDER_URL_HOST) || trimmed.contains("example.com")) {
            return@withContext UpdateState.NotConfigured(
                "Update server not configured. Set your manifest URL in Settings to check for updates."
            )
        }

        val (currentCode, currentName) = getCurrentVersion()
        try {
            val conn = openConnectionWithRedirects(trimmed)
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WakeUp-Android/$currentName")
            }

            val status = conn.responseCode
            if (status !in 200..299) {
                return@withContext UpdateState.Error(
                    "Server returned HTTP $status. Please ensure your update manifest JSON is hosted and accessible at this URL."
                )
            }

            val rawJson = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parseManifest(rawJson, currentCode, currentName)
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Network unavailable during update check: ${e.message}")
            UpdateState.Error("No internet connection available. Please connect to Wi-Fi or mobile data and try again.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            UpdateState.Error("Could not check for updates: ${e.localizedMessage ?: "Network or server timeout"}")
        }
    }

    suspend fun downloadApk(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(dir, "WakeUp-${info.versionName}.apk")
        if (targetFile.exists()) targetFile.delete()

        val conn = openConnectionWithRedirects(info.downloadUrl)
        conn.apply {
            requestMethod = "GET"
        }

        val totalLength = conn.contentLength
        conn.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var lastReportedPercent = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    if (totalLength > 0) {
                        val percent = ((downloaded * 100) / totalLength).toInt()
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
        }
        conn.disconnect()

        require(targetFile.exists() && targetFile.length() > 50_000L) {
            targetFile.delete()
            "Downloaded file appears incomplete (${targetFile.length()} bytes)."
        }

        // Verify SHA-256 integrity against manifest
        val computedSha256 = calculateSha256(targetFile)
        if (!info.sha256.isNullOrBlank()) {
            if (!computedSha256.equals(info.sha256.trim(), ignoreCase = true)) {
                targetFile.delete()
                throw SecurityException(
                    "Security verification failed: APK SHA-256 ($computedSha256) does not match expected manifest hash (${info.sha256})."
                )
            }
        }

        // Validate package integrity using Android's PackageManager
        val archiveInfo = context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
        require(archiveInfo != null) {
            targetFile.delete()
            "Downloaded file is not a valid Android APK package."
        }
        require(archiveInfo.packageName == context.packageName) {
            targetFile.delete()
            "Security verification failed: downloaded APK package name '${archiveInfo.packageName}' does not match '${context.packageName}'."
        }

        val archiveVersionCode = if (Build.VERSION.SDK_INT >= 28) {
            archiveInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode
        }
        require(archiveVersionCode == info.versionCode) {
            targetFile.delete()
            "Version verification failed: downloaded APK version code ($archiveVersionCode) does not match manifest version code (${info.versionCode})."
        }

        targetFile
    }

    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }

    fun launchPackageInstaller(apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Grant URI read permission to all resolving package installer activities
        val resolveList = context.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
        for (resolveInfo in resolveList) {
            context.grantUriPermission(resolveInfo.activityInfo.packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(installIntent)
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
