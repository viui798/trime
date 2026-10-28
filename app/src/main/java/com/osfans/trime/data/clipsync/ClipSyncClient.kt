/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.clipsync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 最多读 [limit] 字节，超出直接失败（照抄中州鹅对传输体积的防御）。 */
internal fun InputStream.readAtMostBytes(limit: Long): ByteArray {
    val declared = runCatching { available().toLong() }.getOrDefault(0L)
    require(declared <= limit) { "Payload of $declared bytes exceeds limit" }
    val buffer = ByteArrayOutputStream(minOf(declared + 1, 1L * 1024 * 1024).toInt().coerceAtLeast(8192))
    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(chunk)
        if (read <= 0) break
        total += read
        if (total > limit) throw IOException("Payload exceeds ${limit / (1024 * 1024)}MB")
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

/**
 * SyncClipboard 协议客户端（移植自中州鹅 clipboardsync/network/SyncClient.kt）。
 *
 * 协议面（HTTP Basic 认证）：
 * - `GET/PUT <base>/SyncClipboard.json`：轮询与推送剪贴板元数据。
 *   JSON 形如 `{"Type":"Text","Text":"…","Hash":"<sha256 小写 hex>","HasData":false,"Size":N}`。
 * - `GET/PUT <base>/file/<name>`：二进制内容（图片/文件）。推送时先传文件再写 JSON
 *   （`Type=File`、`DataName=<name>`、`HasData=true`），拉取侧看到 `HasData=true` 就来下载。
 *
 * 哈希约定与中州鹅一致：文本 = SHA-256(内容)；文件上传时留空让服务器自算。
 */
class ClipSyncClient(
    serverUrl: String,
    username: String,
    password: String,
) {
    @Serializable
    data class ClipboardData(
        @SerialName("Type")
        @kotlinx.serialization.json.JsonNames("type")
        val type: String = "Text",
        @SerialName("Text") val text: String = "",
        @SerialName("Hash") val hash: String = "",
        @SerialName("HasData") val hasData: Boolean = false,
        @SerialName("DataName") val dataName: String = "",
        @SerialName("Size") val size: Long = 0,
    )

    private val baseUrl = normalizeBase(serverUrl)
    private val jsonUrl = "${baseUrl}SyncClipboard.json"
    private val credential = Credentials.basic(username, password)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** 轮询远端剪贴板。返回 `(数据, ETag)`；远端没变时数据为 `null`。 */
    @Throws(IOException::class)
    fun getClipboard(etag: String?): Pair<ClipboardData?, String?> {
        val builder =
            Request
                .Builder()
                .url(jsonUrl)
                .header("Authorization", credential)
                .get()
        if (!etag.isNullOrEmpty()) builder.header("If-None-Match", etag)

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 304) return null to (response.header("ETag") ?: etag)
            if (!response.isSuccessful) throw IOException("SyncClipboard GET failed: ${response.code}")
            val newEtag = response.header("ETag")
            var body = response.body?.string()
            if (body == null) return null to newEtag
            if (body.startsWith("\uFEFF")) body = body.substring(1)
            return json.decodeFromString<ClipboardData>(body) to newEtag
        }
    }

    fun putText(text: String) {
        val data =
            ClipboardData(
                type = "Text",
                text = text,
                hash = sha256Hex(text),
                hasData = false,
                size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            )
        putJson(data)
    }

    /** 先传文件本体再写 JSON；与中州鹅一致，文件哈希留空。 */
    fun putFile(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ) {
        val encoded = URLEncoder.encode(fileName, "UTF-8")
        val fileRequest =
            Request
                .Builder()
                .url("${baseUrl}file/$encoded")
                .header("Authorization", credential)
                .put(bytes.toRequestBody(mimeType.toMediaType()))
                .build()
        client.newCall(fileRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("SyncClipboard file upload failed: ${response.code}")
        }
        putJson(
            ClipboardData(
                type = "File",
                text = fileName,
                hash = "",
                hasData = true,
                dataName = fileName,
                size = bytes.size.toLong(),
            ),
        )
    }

    /** 下载 `file/<dataName>`，返回内容字节与服务器给的 Content-Type。 */
    @Throws(IOException::class)
    fun downloadFile(dataName: String): Pair<ByteArray, String> {
        val encoded = URLEncoder.encode(dataName, "UTF-8")
        val request =
            Request
                .Builder()
                .url("${baseUrl}file/$encoded")
                .header("Authorization", credential)
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("SyncClipboard file download failed: ${response.code}")
            val body = response.body ?: throw IOException("SyncClipboard file download: empty body")
            val bytes = body.byteStream().use { it.readAtMostBytes(MAX_TRANSFER_BYTES) }
            return bytes to (body.contentType()?.toString() ?: "*/*")
        }
    }

    /** 测试连接：拉一次元数据，返回耗时毫秒。 */
    @Throws(IOException::class)
    fun testConnection(): Long {
        val started = System.currentTimeMillis()
        client
            .newCall(
                Request
                    .Builder()
                    .url(jsonUrl)
                    .header("Authorization", credential)
                    .get()
                    .build(),
            ).execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.bytes()
            }
        return System.currentTimeMillis() - started
    }

    private fun putJson(data: ClipboardData) {
        val request =
            Request
                .Builder()
                .url(jsonUrl)
                .header("Authorization", credential)
                .put(json.encodeToString(ClipboardData.serializer(), data).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("SyncClipboard PUT failed: ${response.code}")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TRANSFER_BYTES = 32L * 1024 * 1024

        /** 与中州鹅一致：文本哈希 = SHA-256，小写 hex。 */
        fun sha256Hex(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** 规范化服务器地址：补 scheme、保证以 `/` 结尾，使得 `<base>SyncClipboard.json` 可拼。 */
        fun normalizeBase(serverUrl: String): String {
            var url = serverUrl.trim()
            if (!url.contains("://")) url = "http://$url"
            url = url.trimEnd('/')
            if (url.endsWith("SyncClipboard.json", ignoreCase = true)) {
                return "${url.substringBeforeLast("SyncClipboard.json")}"
            }
            return "$url/"
        }
    }
}
