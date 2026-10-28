/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.clipsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.systemservices.clipboardManager
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 跨设备剪贴板同步服务（移植自中州鹅 clipboardsync/MainService 的 SyncClipboard 分支）。
 *
 * 双向数据流与防回环沿用中州鹅：
 * - 远端 → 本地：轮询 `SyncClipboard.json`；文本直接写系统剪贴板，带 `HasData` 的先下载
 *   `file/<DataName>` 存到存储位置（未配置就用应用内目录），再把本地 content:// 写进系统剪贴板。
 *   历史收录交给 [com.osfans.trime.data.db.ClipboardHelper]（文本自动进历史；图片只进系统剪贴板）。
 * - 本地 → 远端：监听系统剪贴板，`item.uri` 优先（图片/文件），否则取文本；对应上传
 *   `file/<name>` + JSON 或纯文本 JSON。
 * - 回声抑制：记住最近拉到 / 最近推过的内容哈希，命中的变化不上传、不回写。
 *   文件条目另记「本地 URI」，防止拉下来的图又被当本地新内容推回去。
 */
class ClipSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var etag: String? = null

    @Volatile
    private var lastPulledHash: String? = null

    @Volatile
    private var lastPushedHash: String? = null

    /** 拉取落地后的本地 URI，防止自己的回写被监听器当成新内容再上传。 */
    @Volatile
    private var lastPulledLocalUri: String? = null
    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    private val clipPrefs get() = AppPrefs.defaultInstance().clipSync
    private var client: ClipSyncClient? = null
    private var clientConfig: Triple<String, String, String>? = null

    private val handler = Handler(Looper.getMainLooper())
    private var screenshotObserver: ContentObserver? = null
    private var screenshotPollJob: Job? = null
    private val handledScreenshots = LinkedHashSet<String>()

    private fun obtainClient(): ClipSyncClient? {
        val config =
            Triple(
                clipPrefs.serverAddress.getValue(),
                clipPrefs.username.getValue(),
                clipPrefs.password.getValue(),
            )
        if (config.first.isBlank()) return null
        if (config != clientConfig) {
            clientConfig = config
            client = ClipSyncClient(config.first, config.second, config.third)
            etag = null
        }
        return client
    }

    @Keep
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onLocalClipChanged()
    }

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        pollJob?.cancel()
                        stopScreenshotWatcher()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        startPolling()
                        startScreenshotWatcherIfNeeded()
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        loadHandledScreenshots()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            null,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        startPolling()
        startScreenshotWatcherIfNeeded()
        Timber.i("ClipSync service started")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        unregisterReceiver(screenReceiver)
        stopScreenshotWatcher()
        pollJob?.cancel()
        screenshotPollJob?.cancel()
        scope.cancel()
        Timber.i("ClipSync service stopped")
        super.onDestroy()
    }

    /** Android 15 起对 dataSync 型前台服务有每日运行时长限额，超时会被系统回调这里。 */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(): Notification {
        val host = runCatching { java.net.URI(clipPrefs.serverAddress.getValue()).host }.getOrNull()
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clipboard_24)
            .setContentTitle(getString(R.string.clip_sync_notification_title))
            .setContentText(
                host?.let { getString(R.string.clip_sync_notification_text, it) }
                    ?: getString(R.string.clip_sync_notification_text_none),
            ).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.clip_sync_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            )
        manager.createNotificationChannel(channel)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob =
            scope.launch {
                while (isActive) {
                    runCatching { pullOnce() }.onFailure { Timber.w("ClipSync pull failed: $it") }
                    delay(clipPrefs.interval.getValue().coerceAtLeast(1) * 1_000L)
                }
            }
    }

    // ---------- 拉取方向 ----------

    private suspend fun pullOnce() {
        val client = obtainClient() ?: return
        val (data, newEtag) = client.getClipboard(etag)
        etag = newEtag
        data ?: return
        if (data.type.equals("Text", ignoreCase = true) && !data.hasData) {
            if (data.text.isBlank()) return
            val hash = data.hash.ifBlank { ClipSyncClient.sha256Hex(data.text) }
            if (hash == lastPulledHash || hash == lastPushedHash) return
            if (!acceptIncoming(data.dataName, data.text.length.toLong())) return
            lastPulledHash = hash
            lastPulledLocalUri = null
            writeSystemClipboard(ClipData.newPlainText(SOURCE_TAG, data.text))
            return
        }
        // 图片 / 文件条目：HasData=true，去 file/<DataName> 下载
        val dataName = data.dataName.ifBlank { data.text }
        if (dataName.isBlank()) return
        if (!acceptIncoming(dataName, data.size)) return
        val key = ClipSyncClient.sha256Hex("$dataName|${data.size}|${data.hash}")
        if (key == lastPulledHash) return
        val (bytes, mimeType) = client.downloadFile(dataName)
        val localUri = saveIncoming(dataName, bytes, mimeType) ?: return
        lastPulledHash = key
        lastPulledLocalUri = localUri.toString()
        writeSystemClipboard(ClipData.newUri(contentResolver, SOURCE_TAG, localUri))
    }

    /** 接收过滤（同步过滤）：扩展名黑名单 + 文件大小上限；没启用就全收。 */
    private fun acceptIncoming(
        dataName: String,
        sizeBytes: Long,
    ): Boolean {
        if (!clipPrefs.filterEnabled.getValue()) return true
        val blocked =
            clipPrefs.filterExtensions
                .getValue()
                .split(',', '，', ' ', '\n')
                .map { it.trim().trimStart('.').lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        if (blocked.isNotEmpty()) {
            val ext = dataName.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ext in blocked) return false
        }
        val maxMb = clipPrefs.filterMaxFileMb.getValue()
        if (maxMb > 0 && sizeBytes > maxMb * 1024L * 1024L) return false
        return true
    }

    /**
     * 落地一份拉取的文件：优先写进「存储位置」（SAF 目录），失败或未配置就放应用内
     * `files/clip-sync/`。返回可供剪贴板引用的 content:// URI（FileProvider）。
     */
    private fun saveIncoming(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): Uri? {
        val safeName = fileName.substringAfterLast('/').ifBlank { "clip-${System.currentTimeMillis()}" }
        runCatching { saveIntoSafDir(safeName, bytes, mimeType) }.getOrNull()?.let { return it }
        runCatching {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "clip-sync")
            dir.mkdirs()
            var target = File(dir, safeName)
            if (target.exists()) {
                val dot = safeName.lastIndexOf('.')
                val base = if (dot > 0) safeName.substring(0, dot) else safeName
                val ext = if (dot > 0) safeName.substring(dot) else ""
                target = File(dir, "$base-${System.currentTimeMillis()}$ext")
            }
            target.writeBytes(bytes)
            return FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        }.onFailure { Timber.w("ClipSync failed to save incoming file: $it") }
        return null
    }

    private fun saveIntoSafDir(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): Uri? {
        val treeUri = Uri.parse(clipPrefs.storageUri.getValue())
        if (treeUri.scheme != "content") return null
        val resolver = contentResolver
        val docUri =
            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                android.provider.DocumentsContract.getTreeDocumentId(treeUri),
            )
        val created =
            android.provider.DocumentsContract.createDocument(resolver, docUri, mimeType.ifBlank { "*/*" }, fileName)
            ?: return null
        resolver.openOutputStream(created)?.use { it.write(bytes) } ?: return null
        return created
    }

    private fun writeSystemClipboard(clip: ClipData) {
        runCatching {
            clipboardManager.setPrimaryClip(clip)
        }.onFailure { Timber.w("ClipSync failed to write system clipboard: $it") }
    }

    // ---------- 上传方向 ----------

    private fun onLocalClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        // 与 ClipboardHelper 相同的重复回调去抖（API 26 用系统时间戳，否则 100ms 内比 hash）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val now = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (now - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = now
            lastClipHash = hash
        }
        val item = clip.getItemAt(0)
        val uri = item.uri?.toString()
        val content =
            uri
                ?: item.text?.toString()?.takeIf { it.isNotEmpty() }
                ?: item.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
                ?: return
        if (content == lastPulledLocalUri) return
        val hash = ClipSyncClient.sha256Hex(content)
        if (hash == lastPulledHash || hash == lastPushedHash) return
        scope.launch { upload(content, uri != null) }
    }

    private suspend fun upload(
        content: String,
        isUri: Boolean,
    ) {
        val client = obtainClient() ?: return
        runCatching {
            if (isUri) {
                val uri = Uri.parse(content)
                val name = queryFileName(uri) ?: "clip-${System.currentTimeMillis()}.png"
                val bytes =
                    contentResolver.openInputStream(uri)?.use { it.readAtMostBytes(MAX_TRANSFER_BYTES) }
                        ?: throw IOException("Clipboard URI no longer readable: $content")
                val mime = contentResolver.getType(uri) ?: guessMime(name)
                client.putFile(name, bytes, mime)
            } else {
                client.putText(content)
            }
            lastPushedHash = ClipSyncClient.sha256Hex(content)
        }.onFailure { Timber.w("ClipSync push failed: $it") }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return runCatching {
            contentResolver
                .query(uri, null, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "*/*"
        }

    // ---------- 同步新截屏 ----------

    private fun hasImageReadPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startScreenshotWatcherIfNeeded() {
        if (!clipPrefs.enabled.getValue() || !clipPrefs.screenshotSync.getValue()) return
        if (!hasImageReadPermission()) return
        if (screenshotObserver != null) return
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    scheduleScreenshotQuery()
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    scheduleScreenshotQuery()
                }
            }
        screenshotObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
        }.onFailure { Timber.w("ClipSync screenshot observer failed: $it") }
        screenshotPollJob =
            scope.launch {
                // ContentObserver 在部分 ROM 上不可靠，照抄中州鹅补一个低频轮询兜底
                while (isActive) {
                    delay(5_000L)
                    queryScreenshots()
                }
            }
        scope.launch { queryScreenshots() }
    }

    private fun stopScreenshotWatcher() {
        screenshotObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        screenshotObserver = null
        screenshotPollJob?.cancel()
        screenshotPollJob = null
    }

    private var screenshotQueryScheduled = false

    private fun scheduleScreenshotQuery() {
        if (screenshotQueryScheduled) return
        screenshotQueryScheduled = true
        scope.launch {
            delay(1_200L)
            screenshotQueryScheduled = false
            queryScreenshots()
        }
    }

    private suspend fun queryScreenshots() {
        if (!clipPrefs.enabled.getValue() || !clipPrefs.screenshotSync.getValue()) return
        if (!hasImageReadPermission()) return
        runCatching {
            val windowStart = System.currentTimeMillis() / 1000 - SCREENSHOT_WINDOW_SECONDS
            val collection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            contentResolver
                .query(
                    collection,
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DISPLAY_NAME,
                    ),
                    "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                    arrayOf(windowStart.toString()),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    var scanned = 0
                    while (cursor.moveToNext() && scanned < 5) {
                        scanned++
                        val id = cursor.getLong(0)
                        val dateAdded = cursor.getLong(1)
                        val name = cursor.getString(2).orEmpty()
                        val key = "$id:$dateAdded"
                        if (key in handledScreenshots) continue
                        val pathish = name.lowercase()
                        if (SCREENSHOT_MARKERS.none { it in pathish }) continue
                        handledScreenshots.add(key)
                        while (handledScreenshots.size > 256) {
                            handledScreenshots.remove(handledScreenshots.first())
                        }
                        persistHandledScreenshots()
                        handleNewScreenshot(ContentUris.withAppendedId(collection, id), name)
                    }
                }
        }.onFailure { Timber.w("ClipSync screenshot query failed: $it") }
    }

    private suspend fun handleNewScreenshot(
        uri: Uri,
        name: String,
    ) {
        val client = obtainClient() ?: return
        runCatching {
            val bytes =
                contentResolver.openInputStream(uri)?.use { it.readAtMostBytes(MAX_TRANSFER_BYTES) }
                    ?: return
            val mime = guessMime(name)
            client.putFile(name, bytes, mime)
            lastPushedHash = ClipSyncClient.sha256Hex(uri.toString())
            // 截屏进系统剪贴板，当前应用里可以直接粘贴
            lastPulledLocalUri = null
            writeSystemClipboard(ClipData.newUri(contentResolver, SOURCE_TAG, uri))
            Timber.i("ClipSync uploaded screenshot $name")
        }.onFailure { Timber.w("ClipSync screenshot sync failed: $it") }
    }

    private fun loadHandledScreenshots() {
        val raw = clipPrefs.enabled.sharedPreferences.getString(clipHandledKey(), null) ?: return
        raw.split("\n").filter { it.isNotBlank() }.forEach { handledScreenshots.add(it) }
    }

    private fun persistHandledScreenshots() {
        clipPrefs.enabled.sharedPreferences
            .edit()
            .putString(clipHandledKey(), handledScreenshots.joinToString("\n"))
            .apply()
    }

    private fun clipHandledKey() = AppPrefs.ClipSync.HANDLED_SCREENSHOTS

    companion object {
        private const val CHANNEL_ID = "clip_sync"
        private const val NOTIFICATION_ID = 20261
        private const val SOURCE_TAG = "clip_sync"
        private const val SCREENSHOT_WINDOW_SECONDS = 120L
        private const val MAX_TRANSFER_BYTES = 32L * 1024 * 1024
        private val SCREENSHOT_MARKERS = listOf("screenshot", "screen_shot", "screen-shot", "截屏", "截图")

        /** 测试连接：供设置页调用，返回耗时毫秒。 */
        fun testConnection(
            address: String,
            username: String,
            password: String,
        ): Long = ClipSyncClient(address, username, password).testConnection()

        /** 测试推送：把当前系统剪贴板内容强制推到服务器。 */
        suspend fun testPush(
            address: String,
            username: String,
            password: String,
            text: String,
        ) {
            ClipSyncClient(address, username, password).putText(text)
        }
    }
}

/** 前台服务的启停入口：pref 开关、Application 创建、输入框唤起三条路都会调到。 */
object ClipSync {
    private val prefs get() = AppPrefs.defaultInstance().clipSync

    // WeakHashSet 只弱引用 listener，必须留在字段上防止被回收（见 PreferenceDelegate 警告）
    @Keep
    private val enabledListener =
        PreferenceDelegate.OnChangeListener<Boolean> { _, enabled ->
            val context = TrimeApplication.getInstance()
            if (enabled) {
                ensureStarted(context)
            } else {
                context.stopService(Intent(context, ClipSyncService::class.java))
            }
        }

    fun init(context: Context) {
        prefs.enabled.registerOnChangeListener(enabledListener)
        if (prefs.enabled.getValue()) ensureStarted(context)
    }

    /**
     * API 31+ 在后台起前台服务会被拒（ForegroundServiceStartNotAllowedException）。
     * 应用刚被系统拉起（比如 IME 重绑）时可能还在后台，失败就等下次输入框唤起再试。
     */
    fun ensureStarted(context: Context) {
        if (!prefs.enabled.getValue()) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClipSyncService::class.java),
            )
        }.onFailure { Timber.w("ClipSync start deferred: $it") }
    }
}
