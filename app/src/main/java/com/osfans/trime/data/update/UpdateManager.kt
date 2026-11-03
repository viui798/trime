/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.osfans.trime.BuildConfig
import com.osfans.trime.util.Const
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String = "",
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

class ChecksumMissingException : IllegalStateException("checksum file missing")

class ChecksumMismatchException : IllegalStateException("checksum mismatch")

/**
 * 芝版同文：检查 GitHub Release、下载对应 ABI 的 APK、校验后交给系统安装器。
 *
 * 这里不静默安装：Android 必须由用户在系统安装器里确认。SHA-256 来自 Release
 * 里的 `SHA256SUMS.txt`，签名是否匹配仍由 Android 安装器最终判断。
 */
object UpdateManager {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun currentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis } ?: "arm64-v8a"

    suspend fun fetchLatest(): GitHubRelease =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(Const.ZHI_API_LATEST_RELEASE)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "TrimeCheese/${BuildConfig.VERSION_NAME}")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) error("empty response")
                json.decodeFromString<GitHubRelease>(text)
            }
        }

    fun pickAsset(
        release: GitHubRelease,
        abi: String = currentAbi(),
    ): GitHubAsset? = release.assets.firstOrNull { it.name == "trime-cheese-$abi.apk" }

    /** `v1.2.14-cheese` > `1.2.13-cheese`；无法解析远端版本时不提示更新。 */
    fun isNewer(
        remoteTag: String,
        currentVersion: String,
    ): Boolean {
        val remote = versionParts(remoteTag) ?: return false
        val current = versionParts(currentVersion) ?: return true
        for (i in 0 until maxOf(remote.size, current.size)) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    internal fun versionParts(version: String): List<Int>? =
        Regex("""\d+(?:\.\d+)+""")
            .find(version)
            ?.value
            ?.split('.')
            ?.mapNotNull { it.toIntOrNull() }

    /**
     * 下载并校验 [asset]；返回缓存里的 APK。进度回调在主线程。
     */
    suspend fun download(
        context: Context,
        release: GitHubRelease,
        asset: GitHubAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val checksumAsset =
                release.assets.firstOrNull { it.name == "SHA256SUMS.txt" }
                    ?: throw ChecksumMissingException()
            val expected =
                fetchChecksums(checksumAsset.downloadUrl)[asset.name]
                    ?: throw ChecksumMissingException()
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val temp = File(dir, "${target.name}.part")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = asset.size
            try {
                val request =
                    Request
                        .Builder()
                        .url(asset.downloadUrl)
                        .header("User-Agent", "TrimeCheese/${BuildConfig.VERSION_NAME}")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty body")
                    if (body.contentLength() > 0) total = body.contentLength()
                    body.byteStream().use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastNotify = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                downloaded += read
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastNotify >= 100 || (total > 0 && downloaded >= total)) {
                                    lastNotify = now
                                    withContext(Dispatchers.Main) { onProgress(downloaded, total) }
                                }
                            }
                            if (total <= 0) total = downloaded
                            withContext(Dispatchers.Main) { onProgress(downloaded, total) }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw ChecksumMismatchException()
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) error("cannot save download")
                target
            } catch (t: Throwable) {
                temp.delete()
                throw t
            }
        }

    private suspend fun fetchChecksums(url: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "TrimeCheese/${BuildConfig.VERSION_NAME}")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                parseChecksums(response.body?.string().orEmpty())
            }
        }

    internal fun parseChecksums(text: String): Map<String, String> =
        text
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[0].length == 64) {
                    parts[1] to parts[0].lowercase()
                } else {
                    null
                }
            }.toMap()

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun install(
        context: Context,
        file: File,
    ): Boolean {
        if (!canInstall(context)) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    fun releasePage(tag: String): String = "${Const.ZHI_RELEASES_URL}/tag/$tag"
}
