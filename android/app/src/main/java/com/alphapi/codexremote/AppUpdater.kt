package com.alphapi.codexremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/melompeel/codex-desktop-android-remote/releases/latest"

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
    data class Downloading(val update: AppUpdate) : UpdateState
    data class ReadyToInstall(val update: AppUpdate, val apk: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
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

    suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "codex-desktop-remote-${update.version}.apk")
        val temporary = File(directory, "${target.name}.download")
        val request = Request.Builder()
            .url(update.apkUrl)
            .header("User-Agent", "Codex-Desktop-Remote-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：GitHub 返回 ${response.code}")
            val body = response.body ?: error("下载内容为空")
            temporary.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
        }
        if (temporary.length() < 4 || temporary.inputStream().use { input ->
                input.read() != 'P'.code || input.read() != 'K'.code
            }
        ) {
            temporary.delete()
            error("下载的文件不是有效 APK")
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) error("无法保存下载的 APK")
        target
    }

    fun install(apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

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
