package com.alphapi.codexremote

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/melompeel/codex-desktop-android-remote/releases/latest"
private const val UPDATE_PREFERENCES = "app_update_download"
private const val UPDATE_CHANNEL = "app_updates"
private const val UPDATE_NOTIFICATION_ID = 4202

data class AppUpdate(
    val version: String,
    val apkUrl: String,
    val releasePageUrl: String,
    val notes: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val downloadId: Long) : UpdateState
    data class ReadyToInstall(val update: AppUpdate, val downloadId: Long) : UpdateState
    data class Error(val message: String) : UpdateState
}

internal enum class DownloadStatus { ACTIVE, READY, FAILED, MISSING }

internal fun classifyDownloadStatus(status: Int?): DownloadStatus = when (status) {
    DownloadManager.STATUS_PENDING,
    DownloadManager.STATUS_RUNNING,
    DownloadManager.STATUS_PAUSED -> DownloadStatus.ACTIVE
    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.READY
    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
    else -> DownloadStatus.MISSING
}

internal fun updateApkFileName(version: String): String =
    "codex-desktop-remote-${normalizeVersion(version) ?: "update"}.apk"

class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        currentDownloadState()?.let { return@withContext it }
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Codex-Desktop-Remote-Android/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext UpdateState.Current
                if (!response.isSuccessful) error("GitHub 返回 ${response.code}")
                val release = json.decodeFromString<GitHubRelease>(
                    response.body?.string() ?: error("GitHub 没有返回版本信息"),
                )
                val version = normalizeVersion(release.tagName)
                    ?: error("无法识别版本号 ${release.tagName}")
                if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
                    return@withContext UpdateState.Current
                }
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: error("新版本没有附带 APK 文件")
                UpdateState.Available(
                    AppUpdate(version, apk.downloadUrl, release.htmlUrl, release.body.trim()),
                )
            }
        } catch (error: Exception) {
            UpdateState.Error(error.message ?: "检查更新失败")
        }
    }

    fun enqueue(update: AppUpdate): UpdateState {
        val existing = currentDownloadState()
        if (existing is UpdateState.Downloading && existing.update.version == update.version) return existing
        if (existing is UpdateState.ReadyToInstall && existing.update.version == update.version) return existing
        clearStoredDownload(removeFromManager = true)
        val fileName = updateApkFileName(update.version)
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(fileName)
            ?.delete()
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Codex Desktop Remote ${update.version}")
            .setDescription("正在下载应用更新")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = downloads.enqueue(request)
        storeDownload(downloadId, update)
        return UpdateState.Downloading(update, downloadId)
    }

    fun currentDownloadState(): UpdateState? {
        val stored = readStoredDownload() ?: return null
        if (compareVersions(stored.update.version, BuildConfig.VERSION_NAME) <= 0) {
            clearStoredDownload(removeFromManager = true)
            return null
        }
        return when (classifyDownloadStatus(queryStatus(stored.downloadId))) {
            DownloadStatus.ACTIVE -> UpdateState.Downloading(stored.update, stored.downloadId)
            DownloadStatus.READY -> {
                if (downloads.getUriForDownloadedFile(stored.downloadId) == null) {
                    clearStoredDownload()
                    null
                } else UpdateState.ReadyToInstall(stored.update, stored.downloadId)
            }
            DownloadStatus.FAILED, DownloadStatus.MISSING -> {
                clearStoredDownload()
                null
            }
        }
    }

    fun install(downloadId: Long) {
        context.startActivity(
            Intent(context, UpdateInstallActivity::class.java)
                .putExtra(UpdateInstallActivity.EXTRA_DOWNLOAD_ID, downloadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun handleDownloadComplete(downloadId: Long) {
        val ready = currentDownloadState() as? UpdateState.ReadyToInstall ?: return
        if (ready.downloadId != downloadId) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val install = Intent(context, UpdateInstallActivity::class.java)
            .putExtra(UpdateInstallActivity.EXTRA_DOWNLOAD_ID, downloadId)
        val pending = PendingIntent.getActivity(
            context,
            downloadId.hashCode(),
            install,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            UPDATE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, UPDATE_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Codex Desktop Remote ${ready.update.version} 已下载")
                .setContentText("点击安装更新")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun queryStatus(downloadId: Long): Int? = runCatching {
        downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }.getOrNull()

    private fun storeDownload(downloadId: Long, update: AppUpdate) {
        preferences.edit()
            .putLong("download_id", downloadId)
            .putString("version", update.version)
            .putString("apk_url", update.apkUrl)
            .putString("release_url", update.releasePageUrl)
            .putString("notes", update.notes)
            .apply()
    }

    private fun readStoredDownload(): StoredDownload? {
        if (!preferences.contains("download_id")) return null
        val version = preferences.getString("version", null)?.takeIf(String::isNotBlank) ?: return null
        val apkUrl = preferences.getString("apk_url", null)?.takeIf(String::isNotBlank) ?: return null
        return StoredDownload(
            preferences.getLong("download_id", -1L).takeIf { it >= 0 } ?: return null,
            AppUpdate(
                version,
                apkUrl,
                preferences.getString("release_url", "").orEmpty(),
                preferences.getString("notes", "").orEmpty(),
            ),
        )
    }

    private fun clearStoredDownload(removeFromManager: Boolean = false) {
        val downloadId = preferences.getLong("download_id", -1L)
        preferences.edit().clear().apply()
        if (removeFromManager && downloadId >= 0) downloads.remove(downloadId)
    }
}

private data class StoredDownload(val downloadId: Long, val update: AppUpdate)

internal fun normalizeVersion(value: String): String? {
    val normalized = value.trim().removePrefix("v").removePrefix("V")
    return normalized.takeIf { it.matches(Regex("\\d+(?:\\.\\d+){1,3}")) }
}

internal fun compareVersions(left: String, right: String): Int {
    val a = left.split('.').mapNotNull(String::toIntOrNull)
    val b = right.split('.').mapNotNull(String::toIntOrNull)
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)
