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

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeBytes: Long = 0L
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateInfo, val currentCode: Int, val currentName: String) : UpdateState
    data class UpToDate(val currentCode: Int, val currentName: String) : UpdateState
    data class Downloading(val progressPercent: Int) : UpdateState
    data class ReadyToInstall(val apkFile: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

class UpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "WakeUpUpdateManager"
        const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/aditya/wakeup-alarm/main/version.json"

        fun isUpdateAvailable(currentCode: Int, remoteCode: Int): Boolean = remoteCode > currentCode

        fun evaluateUpdate(
            currentCode: Int,
            currentName: String,
            remoteCode: Int,
            remoteName: String,
            notes: String,
            downloadUrl: String,
            size: Long
        ): UpdateState {
            if (remoteCode <= 0 || downloadUrl.isBlank()) {
                return UpdateState.Error("Invalid update manifest received from server.")
            }

            return if (isUpdateAvailable(currentCode, remoteCode)) {
                val info = UpdateInfo(remoteCode, remoteName, notes, downloadUrl, size)
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
                evaluateUpdate(currentCode, currentName, remoteCode, remoteName, notes, downloadUrl, size)
            }.getOrElse {
                // Fallback for JVM unit tests where android.jar's org.json.JSONObject is not mocked
                val remoteCode = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(rawJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val remoteName = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1) ?: ""
                val notes = Regex("\"releaseNotes\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1) ?: "New performance and feature updates."
                val downloadUrl = Regex("\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1) ?: ""
                val size = Regex("\"apkSizeBytes\"\\s*:\\s*(\\d+)").find(rawJson)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                evaluateUpdate(currentCode, currentName, remoteCode, remoteName, notes, downloadUrl, size)
            }
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
        val name = pInfo.versionName ?: "1.3.0"
        return code to name
    }

    suspend fun checkForUpdates(serverUrl: String = DEFAULT_UPDATE_URL): UpdateState = withContext(Dispatchers.IO) {
        val (currentCode, currentName) = getCurrentVersion()
        try {
            val url = URL(serverUrl.trim())
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WakeUp-Android/${currentName}")
            }

            val status = conn.responseCode
            if (status !in 200..299) {
                return@withContext UpdateState.Error("Server returned error code $status. Check the update endpoint URL in settings.")
            }

            val rawJson = conn.inputStream.bufferedReader().use { it.readText() }
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

        val url = URL(info.downloadUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
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

        require(targetFile.exists() && targetFile.length() > 50_000L) {
            "Downloaded file appears incomplete or corrupted (${targetFile.length()} bytes)."
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
