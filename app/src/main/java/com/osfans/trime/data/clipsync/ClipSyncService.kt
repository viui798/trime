/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/*
 * 跨设备剪贴板同步前台服务，完整移植自中州鹅 clipboardsync/MainService.kt（含 a34c935
 * 修复：保存顺序保护 ClipboardTaskQueue、载荷约束 BoundedWebSocketTransport/PayloadBudget、
 * 后台过滤、SyncStateStore 文件化状态）。
 *
 * 相对上游的本地差异：
 * - fcitx 的 RemoteConnection/IClipboardEntryTransformer 单进程内不存在，剪贴板观察
 *   全部走系统 ClipboardManager 监听；远端条目入库用 ClipboardHelper.insertFromSync。
 * - HostClipboardFilter（ClearURLs）未移植，归一化函数为恒等。
 * - 偏好键一律用本地 clip_sync_* 前缀，读写同一份默认 SharedPreferences。
 */
package com.osfans.trime.data.clipsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.osfans.trime.R
import com.osfans.trime.data.clipsync.network.ClipCascadeClient
import com.osfans.trime.data.clipsync.network.ClipCascadeClipboardData
import com.osfans.trime.data.clipsync.network.ClipboardData
import com.osfans.trime.data.clipsync.network.HashUtils
import com.osfans.trime.data.clipsync.network.OneClipEventClient
import com.osfans.trime.data.clipsync.network.SyncClient
import com.osfans.trime.data.clipsync.network.SyncClient.ServerBackend
import com.osfans.trime.data.clipsync.service.QuickSyncTileService
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.ui.clipsync.ClipboardCaptureActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splitties.systemservices.clipboardManager
import timber.log.Timber
import java.io.IOException
import java.util.Locale

class ClipSyncService : Service() {

    companion object {
        const val PREF_QUICK_SYNC = "clip_sync_enabled"
        const val EXTRA_STARTED_BY = "com.osfans.trime.clipsync.extra.STARTED_BY"
        const val EXTRA_IME_ACTIVE = "com.osfans.trime.clipsync.extra.IME_ACTIVE"
        const val PREF_QUICK_SYNC_UNREACHABLE = "clip_sync_unreachable"
        private const val DEFAULT_QUICK_SYNC_ENABLED = false
        private const val PREF_SCREENSHOT_SYNC = "clip_sync_screenshot"
        private const val PREF_IME_SYNC_ACTIVE = "clip_sync_ime_sync_active"
        private const val PREF_SYNC_INTERVAL = "clip_sync_interval"
        private const val PREF_USERNAME = "clip_sync_username"
        private const val PREF_PASSWORD = "clip_sync_password"
        private const val SERVER_PROFILE_TYPE_KEY = "clip_sync_server_profile_type"
        private const val SERVER_ADDRESS_KEY = "clip_sync_server_address"
        private const val SERVER_ADDRESS_SYNC_CLIPBOARD_KEY = "clip_sync_server_address_syncclipboard"
        private const val SERVER_ADDRESS_ONE_CLIP_KEY = "clip_sync_server_address_oneclip"
        private const val SERVER_ADDRESS_CLIP_CASCADE_KEY = "clip_sync_server_address_clipcascade"
        private const val SERVER_ADDRESS_CUSTOM_KEY = "clip_sync_server_address_custom"
        private const val PROFILE_SYNC_CLIPBOARD = "syncclipboard"
        private const val PROFILE_ONE_CLIP = "oneclip"
        private const val PROFILE_CLIP_CASCADE = "clipcascade"
        private const val PROFILE_CUSTOM = "custom"
        private val CONNECTIVITY_RETRY_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)
        private const val EVENT_BACKEND_HEALTH_CHECK_MS = 30_000L
        private const val EVENT_BACKEND_FALLBACK_PULL_MS = 60_000L
        private const val EVENT_BACKEND_STALE_RECONNECT_MS = 120_000L

        /**
         * Poll interval while the screen is on but no text field is focused (see C17).
         *
         * Between the normal interval and the screen-off one: sync keeps working after the
         * keyboard closes, without polling at typing frequency all day.
         */
        private const val IME_IDLE_POLL_INTERVAL_SECONDS = 30L

        /**
         * Hard cap on an incoming binary clipboard payload (see A9).
         *
         * Applies regardless of the user's receive filter: the whole payload passes through
         * memory, so an unbounded one is an OOM in the IME process.
         */
        private const val MAX_INCOMING_BINARY_BYTES = 32L * 1024 * 1024
        private const val SCREEN_OFF_POLL_INTERVAL_SECONDS = 15L
        private const val POWER_SAVE_POLL_INTERVAL_SECONDS = 30L
        private const val AGGRESSIVE_POLL_INTERVAL_SECONDS = 60L
        private const val SCREEN_OFF_HEALTH_CHECK_MS = 60_000L
        private const val POWER_SAVE_HEALTH_CHECK_MS = 120_000L
        private const val AGGRESSIVE_HEALTH_CHECK_MS = 180_000L
        private const val SCREEN_OFF_FALLBACK_PULL_MS = 180_000L
        private const val POWER_SAVE_FALLBACK_PULL_MS = 240_000L
        private const val AGGRESSIVE_FALLBACK_PULL_MS = 300_000L
        private const val SCREEN_OFF_STALE_RECONNECT_MS = 300_000L
        private const val POWER_SAVE_STALE_RECONNECT_MS = 420_000L
        private const val AGGRESSIVE_STALE_RECONNECT_MS = 600_000L
        private const val NETWORK_RECONNECT_DEBOUNCE_MS = 2_000L
        private const val NOTIFICATION_CHANNEL_ID = "clip_sync"
        private const val NOTIFICATION_ID = 20261
        private const val ACTION_START_SYNC = "com.osfans.trime.clipsync.action.START"
        private const val ACTION_RECONNECT_SYNC = "com.osfans.trime.clipsync.action.RECONNECT"
        private const val ACTION_PAUSE_SYNC = "com.osfans.trime.clipsync.action.PAUSE"
        private const val ACTION_INGEST_CAPTURED_CLIPBOARD = "com.osfans.trime.clipsync.action.INGEST_CAPTURED_CLIPBOARD"
        private const val ACTION_SUPPRESS_REMOTE_CLIPBOARD =
            "com.osfans.trime.clipsync.action.SUPPRESS_REMOTE_CLIPBOARD"
        private const val EXTRA_START_REASON = "start_reason"
        private const val EXTRA_FORCE_ENABLE_SYNC = "force_enable_sync"
        private const val EXTRA_CAPTURED_CLIPBOARD_CONTENT = "captured_clipboard_content"
        private const val EXTRA_SUPPRESSED_REMOTE_ITEMS = "suppressed_remote_items"
        private const val PREF_PENDING_UPLOADS = "clip_sync_pending_uploads"
        private const val PREF_REMOTE_REVISIONS = "clip_sync_remote_revisions"
        private const val PREF_LAST_SYNCED_CONTENT = "clip_sync_last_synced_content"
        private const val PREF_SUPPRESSED_REMOTE_ITEMS = "clip_sync_suppressed_remote_items"
        private const val PREF_RECENT_UPLOADED_FILES = "clip_sync_recent_uploaded_files"
        private const val MAX_PENDING_UPLOADS = 50
        private const val MAX_SUPPRESSED_REMOTE_ITEMS = 256
        private const val MAX_RECENT_UPLOADED_FILES = 64
        private const val RECENT_UPLOADED_FILE_TTL_MS = 10 * 60 * 1000L

        /** Keys moved from the default SharedPreferences into [SyncStateStore]. */
        private val MIGRATED_STATE_KEYS =
            listOf(
                PREF_PENDING_UPLOADS,
                PREF_REMOTE_REVISIONS,
                PREF_LAST_SYNCED_CONTENT,
                PREF_SUPPRESSED_REMOTE_ITEMS,
                PREF_RECENT_UPLOADED_FILES,
            )

        private fun isCredentialStorageUnlocked(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
            return (context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager)?.isUserUnlocked ?: true
        }

        private fun defaultSharedPreferencesOrNull(context: Context): SharedPreferences? =
            try {
                PreferenceManager.getDefaultSharedPreferences(context).also(SyncServerPreferences::migrate)
            } catch (e: IllegalStateException) {
                Timber.i("Skip accessing shared preferences: %s", e.message)
                null
            }

        fun shouldAutoStart(context: Context): Boolean {
            if (!isCredentialStorageUnlocked(context)) return false
            val prefs = defaultSharedPreferencesOrNull(context) ?: return false
            return prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED) &&
                SyncServerPreferences.hasConfiguredCurrent(prefs)
        }

        fun startSyncService(
            context: Context,
            reason: String,
            forceEnableSync: Boolean = false,
            imeSyncActive: Boolean = false,
        ) {
            if (!isCredentialStorageUnlocked(context)) {
                Timber.i("Skip startSyncService(%s): user storage is still locked", reason)
                return
            }
            val prefs = defaultSharedPreferencesOrNull(context) ?: return
            if (!forceEnableSync && !prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)) {
                return
            }
            if (!SyncServerPreferences.hasConfiguredCurrent(prefs)) {
                if (forceEnableSync) {
                    ContextCompat.getMainExecutor(context.applicationContext).execute {
                        android.widget.Toast
                            .makeText(
                                context.applicationContext,
                                R.string.clip_sync_test_no_address,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
                return
            }
            if (imeSyncActive) {
                prefs.edit().putBoolean(PREF_IME_SYNC_ACTIVE, true).apply()
            }
            val intent =
                Intent(context, ClipSyncService::class.java).apply {
                    action = ACTION_START_SYNC
                    putExtra(EXTRA_START_REASON, reason)
                    putExtra(EXTRA_FORCE_ENABLE_SYNC, forceEnableSync)
                }
            context.startService(intent)
        }

        /**
         * Called when the IME loses its input connection.
         *
         * Despite the name this no longer stops syncing while quick sync is on: it clears the
         * "IME in front" flag, which now only lowers the polling frequency, and refreshes the
         * service. Sync used to halt completely here and stay halted (see C17).
         */
        fun stopSyncService(context: Context) {
            if (!isCredentialStorageUnlocked(context)) return
            val prefs = defaultSharedPreferencesOrNull(context) ?: return
            prefs
                .edit()
                .putBoolean(PREF_IME_SYNC_ACTIVE, false)
                .apply()
            if (prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)) {
                startSyncService(context, "ime-stop-refresh")
                return
            }
            runCatching {
                context.stopService(Intent(context, ClipSyncService::class.java))
            }
        }

        fun submitCapturedClipboard(
            context: Context,
            content: String,
            reason: String = "manual-capture",
        ) {
            if (content.isBlank()) return
            val intent =
                Intent(context, ClipSyncService::class.java).apply {
                    action = ACTION_INGEST_CAPTURED_CLIPBOARD
                    putExtra(EXTRA_START_REASON, reason)
                    putExtra(EXTRA_CAPTURED_CLIPBOARD_CONTENT, content)
                }
            context.startService(intent)
        }

        fun suppressRemoteClipboardContents(
            context: Context,
            contents: Collection<String>,
            reason: String = "suppress-remote",
        ) {
            if (contents.isEmpty()) return
            val intent =
                Intent(context, ClipSyncService::class.java).apply {
                    action = ACTION_SUPPRESS_REMOTE_CLIPBOARD
                    putExtra(EXTRA_START_REASON, reason)
                    putStringArrayListExtra(EXTRA_SUPPRESSED_REMOTE_ITEMS, ArrayList(contents.distinct()))
                }
            context.startService(intent)
        }
    }

    private lateinit var prefs: SharedPreferences
    private var syncJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var networkReconnectJob: Job? = null
    private var scope = createScope()
    private val clipboardTasks = ClipboardTaskQueue()
    private var serviceRunning = false
    private var selfStarted = false
    private var stopRequested = false
    private var networkCallbackRegistered = false
    private var foregroundActive = false
    private var screenStateReceiverRegistered = false
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val screenshotClipboardWatcher by lazy {
        ScreenshotClipboardWatcher(this, clipboardManager, ::handleScreenshotClipboardUri)
    }
    private var clipboardListenerRegistered = false
    private var prefsListenerRegistered = false
    private var clipCascadeClient: ClipCascadeClient? = null
    private var oneClipClient: OneClipEventClient? = null
    private var lastNetworkAvailableAt = 0L
    private var connectionSessionId = 0
    private var activeEndpointIdentity: String? = null

    /**
     * Identity of the connection configuration the running sync runtime was built for.
     *
     * Keeps an IME focus change from tearing down and rebuilding a healthy polling/SSE/WebSocket
     * session (see [start]). Deliberately excludes the polling, health, fallback and stale
     * cadences: every loop re-reads [currentRuntimeMode] on each iteration, so a cadence change
     * must not trigger a rebuild.
     */
    private var activeRuntimeFingerprint: String? = null

    // Cache to avoid circular updates (Pull -> Local -> Push -> Loop)
    private var lastLocalContent: String? = null
    private var lastRemoteContent: String? = null
    private var lastRemoteRevision: String? = null
    private var lastUploadedContent: String? = null
    private var lastSuccessfulRemoteSyncAt = 0L
    private var lastBackendActivityAt = 0L
    private var lastClipboardReadFailureLoggedAt = 0L
    private val remoteFetchMutex = Mutex()
    private val pendingUploadMutex = Mutex()
    private val pendingUploadDrainMutex = Mutex()
    private val ignoredRemoteClipboardContents = linkedSetOf<String>()

    /** File-backed replacement for the state that used to live in the default prefs. */
    private lateinit var syncStateStore: SyncStateStore

    private val pendingUploads = mutableListOf<PendingUploadEntry>()
    private val storedRemoteRevisions = mutableMapOf<String, String>()
    private val suppressedRemoteClipboardContents = linkedSetOf<String>()
    private val recentUploadedFiles = mutableListOf<RecentUploadedFile>()

    // Track imported profile IDs to avoid duplicate imports within a sync cycle
    private val importedProfileIdsInCurrentSync = mutableSetOf<String>()
    private val stateJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = SystemClock.elapsedRealtime()
                lastNetworkAvailableAt = now
                scheduleReconnect("network-available")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNetworkAvailableAt >= NETWORK_RECONNECT_DEBOUNCE_MS) {
                        lastNetworkAvailableAt = now
                        scheduleReconnect("network-capabilities")
                    }
                }
            }
        }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleSystemClipboardChanged()
    }

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Timber.d("[Power] Screen turned off, stop sync loops to reduce background power")
                        stopPeriodicSync()
                        stopHealthMonitor()
                        // The screenshot watcher has to stop here too: its ContentObserver plus
                        // its fixed 5-second fallback poll were unaffected by screen-off.
                        // Screen-off also releases the foreground state: holding a dataSync
                        // notification while nothing runs only burns the Android 15 quota. The
                        // process itself is the IME process, so it stays alive regardless;
                        // ACTION_SCREEN_ON re-acquires the foreground state.
                        screenshotClipboardWatcher.stop()
                        updateForegroundState()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        scheduleReconnect("screen-on")
                        // Re-register the watcher that screen-off stopped.
                        updateScreenshotWatcher()
                        // Re-acquire the foreground state dropped at screen-off.
                        updateForegroundState()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        scheduleReconnect("user-present")
                        updateForegroundState()
                    }
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        scheduleReconnect("power-save-mode")
                        // Power save only changes the cadence, not the foreground state, but it
                        // does change active <-> idle in the notification text.
                        refreshForegroundNotification()
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        Timber.d("ClipSyncService onCreate")
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        SyncServerPreferences.migrate(prefs)
        // Service state lives in SyncStateStore files, not in the default SharedPreferences:
        // these values embed clipboard payloads, and the default prefs file is loaded whole
        // into every process that touches PreferenceManager, the IME included.
        syncStateStore = SyncStateStore(this)
        loadPersistentSyncState()
        lastUploadedContent = syncStateStore.read(PREF_LAST_SYNCED_CONTENT)
        // The "IME is in front" flag is persistent but describes a live condition. If the IME
        // process died without onFinishInput (crash, low memory, force stop) it stayed true, and
        // currentRuntimeMode() then reported NORMAL forever — polling at the user's base interval
        // (3s by default) with no keyboard on screen. This service starting fresh means no IME
        // session is attached yet; whoever attaches next sets the flag again.
        prefs.edit().putBoolean(PREF_IME_SYNC_ACTIVE, false).apply()
        createNotificationChannelIfNeeded()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureScope()
        selfStarted = true
        if (intent?.action == ACTION_PAUSE_SYNC) {
            pauseSyncAndStop()
            return START_NOT_STICKY
        }
        if (!SyncServerPreferences.hasConfiguredCurrent(prefs)) {
            stopAndRemoveSelf()
            return START_NOT_STICKY
        }
        // A focus/network refresh may already be queued when the user pauses, or when
        // Android expires the dataSync quota. Only an explicit enable may undo the pause.
        if ((intent?.action == null || intent.action == ACTION_START_SYNC ||
                intent.action == ACTION_RECONNECT_SYNC) &&
            intent?.getBooleanExtra(EXTRA_FORCE_ENABLE_SYNC, false) != true &&
            !prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)
        ) {
            stopAndRemoveSelf()
            return START_NOT_STICKY
        }
        stopRequested = false
        handleActionIntent(intent)
        start()
        return START_NOT_STICKY
    }

    fun start() {
        if (stopRequested) return
        ensureScope()
        if (serviceRunning) {
            // This is the path an IME focus change takes, and focus is what flips
            // active <-> idle in the notification; updateForegroundState rebuilds it.
            updateForegroundState()
            if (stopRequested) return
            // Rebuild only when the connection identity actually changed, or when the runtime is
            // not running at all (the health monitor self-heals a silently dead loop as well).
            val fingerprint = currentRuntimeFingerprint()
            val runtimeAlive = syncJob?.isActive == true || healthMonitorJob?.isActive == true
            if (fingerprint != activeRuntimeFingerprint || !runtimeAlive) {
                refreshSyncRuntime()
            }
            return
        }
        Timber.d("ClipSyncService start")
        serviceRunning = true
        ensureSelfStarted()
        updateForegroundState()
        if (stopRequested) return
        registerClipboardListenerIfNeeded()
        registerPrefsListenerIfNeeded()
        registerNetworkCallbackIfNeeded()
        registerScreenStateReceiverIfNeeded()
        updateScreenshotWatcher()
        refreshSyncRuntime()
        handleSystemClipboardChanged()
    }

    fun stop() {
        if (!serviceRunning) return
        Timber.d("ClipSyncService stop")
        serviceRunning = false
        unregisterClipboardListenerIfNeeded()
        unregisterPrefsListenerIfNeeded()
        unregisterNetworkCallbackIfNeeded()
        unregisterScreenStateReceiverIfNeeded()
        screenshotClipboardWatcher.stop()
        stopPeriodicSync()
        stopHealthMonitor()
        activeRuntimeFingerprint = null
        stopForegroundState()
        connectionSessionId += 1
        scope.coroutineContext.cancelChildren()
    }

    private fun stopAndRemoveSelf() {
        stopRequested = true
        try {
            stop()
            scope.coroutineContext.cancelChildren()
            stopForegroundState()
        } finally {
            selfStarted = false
            // Dropping the notification alone does not satisfy Android 15's timeout
            // contract: the started service must actually stop within a few seconds.
            stopSelf()
        }
    }

    private fun pauseSyncAndStop() {
        // Unregister the preference listener first; changing quick_sync must not
        // re-enter the runtime while it is being torn down.
        stopAndRemoveSelf()
        prefs
            .edit()
            .putBoolean(PREF_QUICK_SYNC, false)
            .putBoolean(PREF_QUICK_SYNC_UNREACHABLE, false)
            .apply()
        QuickSyncTileService.requestTileRefresh(this)
    }

    override fun onDestroy() {
        if (serviceRunning) {
            stop()
        } else {
            connectionSessionId += 1
        }
        scope.cancel()
        selfStarted = false
        super.onDestroy()
    }

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SERVER_ADDRESS_KEY || key == PREF_USERNAME || key == PREF_PASSWORD || key == SERVER_PROFILE_TYPE_KEY) {
                Timber.d("Sync credential/config changed: %s, resetting sync cache", key)
                resetRemoteCache()
                resetFailureState()
                refreshSyncRuntime()
            } else if (
                key == SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS ||
                key == SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE ||
                key == SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE_UNIT ||
                key == SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS ||
                key == SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS
            ) {
                Timber.d("Receive filter changed: %s", key)
                invalidateReceiveFilterCache()
            } else if (key == PREF_QUICK_SYNC || key == PREF_SYNC_INTERVAL || key == PREF_SCREENSHOT_SYNC) {
                if (key == PREF_QUICK_SYNC && !prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)) {
                    stopAndRemoveSelf()
                    QuickSyncTileService.requestTileRefresh(this)
                    return@OnSharedPreferenceChangeListener
                }
                Timber.d("Preference changed: %s, restarting sync", key)
                updateForegroundState()
                if (stopRequested) return@OnSharedPreferenceChangeListener
                updateScreenshotWatcher()
                refreshSyncRuntime()
                if (key == PREF_QUICK_SYNC) {
                    QuickSyncTileService.requestTileRefresh(this)
                }
            }
        }

    private fun handleLocalClipboardUpdate(
        content: String,
        origin: String,
    ) {
        if (content.isBlank()) return
        clipboardTasks.submit(
            scope,
            prepare = {
                // 本地未移植 ClearURLs（HostClipboardFilter），归一化为恒等；
                // isClipboardEcho 的比较语义不受影响。
                val normalizedContent = content
                currentCoroutineContext().ensureActive()
                if (consumeIgnoredRemoteClipboardContent(content) ||
                    consumeIgnoredRemoteClipboardContent(normalizedContent) ||
                    isClipboardEcho(content, normalizedContent, lastRemoteContent, lastUploadedContent) { it }
                ) {
                    return@submit false
                }
                lastLocalContent = normalizedContent
                enqueuePendingUpload(normalizedContent)
            },
            consume = { queued ->
                if (queued) {
                    Timber.d("[Push] Detected local change from %s, queued for upload", origin)
                    flushPendingUploads("local-change:$origin")
                }
            },
        )
    }

    private fun handleScreenshotClipboardUri(uri: String) {
        handleLocalClipboardUpdate(uri, "screenshot-watcher")
        rememberIgnoredRemoteClipboardContent(uri)
    }

    private fun refreshSyncRuntime() {
        if (!shouldRunSyncLoops()) {
            stopPeriodicSync()
            stopHealthMonitor()
            activeRuntimeFingerprint = null
            return
        }
        ensureSelfStarted()
        activeRuntimeFingerprint = currentRuntimeFingerprint()
        startPeriodicSync()
        startHealthMonitor()
    }

    /**
     * Configuration identity the sync runtime is built from.
     *
     * Credentials are intentionally absent: any credential preference change already goes
     * through [prefListener], which resets the remote cache and calls [refreshSyncRuntime]
     * unconditionally, so it never relies on this comparison.
     */
    private fun currentRuntimeFingerprint(): String {
        val endpoint = currentEndpoint()
        return buildString {
            append(endpoint.identity)
            append('|')
            append(endpoint.backend.name)
            append('|')
            append(prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED))
            append('|')
            append(isScreenInteractive())
        }
    }

    private fun updateScreenshotWatcher() {
        if (
            serviceRunning &&
            isScreenInteractive() &&
            prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED) &&
            prefs.getBoolean(PREF_SCREENSHOT_SYNC, false)
        ) {
            screenshotClipboardWatcher.start()
            // Compensating query: the periodic poll only fires after POLL_INTERVAL_MS, and after
            // a screen-on (or a fresh enable) a screenshot from the gap would otherwise wait for
            // the next MediaStore change callback. Safe when already running: scheduleQuery()
            // coalesces into a single pending query.
            screenshotClipboardWatcher.requestImmediateQuery()
        } else {
            screenshotClipboardWatcher.stop()
        }
    }

    private fun startHealthMonitor() {
        if (!shouldRunSyncLoops()) return
        if (healthMonitorJob?.isActive == true) return
        healthMonitorJob =
            scope.launch {
                while (isActive) {
                    if (!shouldRunSyncLoops()) {
                        Timber.d("[Health] Sync loops are disabled, stop health monitor")
                        break
                    }
                    delay(currentHealthCheckDelayMs())
                    if (!shouldRunSyncLoops()) {
                        Timber.d("[Health] Sync loops are disabled after delay, stop health monitor")
                        break
                    }
                    val endpoint = currentEndpoint()
                    val backend = endpoint.backend
                    if (backend == ServerBackend.SYNCCLIPBOARD) {
                        if (syncJob?.isActive != true) {
                            Timber.w("[Health] Polling loop is inactive, restarting")
                            startPeriodicSync()
                        }
                        continue
                    }

                    val connected =
                        when (backend) {
                            ServerBackend.CLIPCASCADE -> clipCascadeClient?.isConnected() == true
                            ServerBackend.ONECLIP -> oneClipClient?.isConnected() == true
                            ServerBackend.SYNCCLIPBOARD -> true
                        }
                    if (!connected || syncJob?.isActive != true) {
                        Timber.w("[Health] Event stream is disconnected, restarting %s", backend)
                        startPeriodicSync()
                        continue
                    }

                    val now = SystemClock.elapsedRealtime()
                    // Only SyncClipboard takes the fallback pull. ClipCascade is a pure event-stream
                    // backend: SyncClient.fetchClipboard throws UnsupportedOperationException for it.
                    // OneClip keeps its fallback: polling is a supported, revision-aware path for it.
                    if (backend == ServerBackend.SYNCCLIPBOARD &&
                        now - lastSuccessfulRemoteSyncAt >= currentFallbackPullDelayMs()
                    ) {
                        try {
                            checkRemoteClipboard()
                            flushPendingUploads("health-fallback")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Timber.e(error, "[Health] Fallback pull failed for %s", endpoint.address)
                            handleConnectivityFailure(endpoint, error)
                        }
                    }

                    if (now - lastBackendActivityAt >= currentStaleReconnectDelayMs()) {
                        Timber.w("[Health] Backend stream appears stale, forcing reconnect for %s", backend)
                        startPeriodicSync()
                    }
                }
            }
    }

    private fun stopHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
    }

    private fun startPeriodicSync() {
        stopPeriodicSync()

        if (!shouldRunSyncLoops()) {
            Timber.d("[Pull] Sync loops paused by runtime state (quick-sync off or screen not interactive)")
            return
        }

        when (currentBackend()) {
            ServerBackend.CLIPCASCADE -> {
                Timber.d("[ClipCascade] Starting persistent websocket sync")
                startClipCascadeSync()
                return
            }

            ServerBackend.ONECLIP -> {
                Timber.d("[OneClip] Starting persistent SSE sync")
                startOneClipSync()
                return
            }

            ServerBackend.SYNCCLIPBOARD -> Unit
        }

        Timber.d("[Pull] Starting periodic sync")
        syncJob =
            scope.launch {
                while (isActive) {
                    if (!shouldRunSyncLoops()) {
                        Timber.d("[Pull] Sync loops are disabled, stop polling loop")
                        break
                    }
                    val endpoint = currentEndpoint()
                    try {
                        val safeInterval =
                            runCatching {
                                prefs.getInt(PREF_SYNC_INTERVAL, 3).toLong()
                            }.getOrElse {
                                prefs.getString(PREF_SYNC_INTERVAL, "3")?.toLongOrNull() ?: 3L
                            }.coerceIn(1, 60)

                        checkRemoteClipboard()
                        resetFailureState()
                        flushPendingUploads("poll-loop")

                        delay(currentPollingIntervalSeconds(safeInterval) * 1000L)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (handleConnectivityFailure(endpoint, e)) {
                            continue
                        }
                        Timber.e(e, "[Pull] Loop error")
                        delay(5000)
                    }
                }
            }
    }

    private fun stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        disconnectClipCascadeClient()
        disconnectOneClipClient()
    }

    private fun startClipCascadeSync() {
        disconnectClipCascadeClient()
        syncJob =
            scope.launch {
                while (isActive) {
                    if (!shouldRunSyncLoops()) {
                        Timber.d("[ClipCascade] Sync loops are disabled, stop websocket loop")
                        break
                    }
                    val endpoint = currentEndpoint()
                    if (endpoint.address.isBlank()) {
                        Timber.d("[ClipCascade] Server address is blank, skipping websocket sync")
                        return@launch
                    }

                    val username = currentUsernameForProfile(endpoint.profileKey)
                    val password = currentPasswordForProfile(endpoint.profileKey)
                    val client =
                        ClipCascadeClient(
                            serverUrl = endpoint.address,
                            username = username,
                            password = password,
                        )
                    clipCascadeClient = client

                    try {
                        client.connect { data ->
                            markBackendActivity()
                            handleClipCascadeMessage(data)
                        }
                        markBackendActivity()
                        resetFailureState()
                        flushPendingUploads("clipcascade-connected")
                        val closeCause = client.awaitClose()
                        if (!isActive) {
                            return@launch
                        }

                        val error = closeCause as? Exception
                            ?: IOException("ClipCascade websocket disconnected")
                        if (handleConnectivityFailure(endpoint, error)) {
                            continue
                        }
                        delay(5000)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (handleConnectivityFailure(endpoint, e)) {
                            continue
                        }
                        Timber.e(e, "[ClipCascade] Connection loop error")
                        delay(5000)
                    } finally {
                        if (clipCascadeClient === client) {
                            clipCascadeClient = null
                        }
                        client.close()
                    }
                }
            }
    }

    private fun startOneClipSync() {
        disconnectOneClipClient()
        syncJob =
            scope.launch {
                while (isActive) {
                    if (!shouldRunSyncLoops()) {
                        Timber.d("[OneClip] Sync loops are disabled, stop SSE loop")
                        break
                    }
                    val endpoint = currentEndpoint()
                    if (endpoint.address.isBlank()) {
                        Timber.d("[OneClip] Server address is blank, skipping SSE sync")
                        return@launch
                    }

                    val client = OneClipEventClient(endpoint.address)
                    oneClipClient = client

                    try {
                        client.connect { event ->
                            markBackendActivity()
                            if (!event.update) {
                                return@connect
                            }
                            scope.launch {
                                runCatching { checkRemoteClipboard(forceRefresh = true) }
                                    .onFailure { error ->
                                        Timber.e(error, "[OneClip] Failed to refresh clipboard after SSE event")
                                    }
                            }
                        }

                        checkRemoteClipboard()
                        markBackendActivity()
                        resetFailureState()
                        flushPendingUploads("oneclip-connected")

                        val closeCause = client.awaitClose()
                        if (!isActive) {
                            return@launch
                        }

                        val error = closeCause as? Exception
                            ?: IOException("OneClip SSE disconnected")
                        if (handleConnectivityFailure(endpoint, error)) {
                            continue
                        }
                        delay(5000)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (handleConnectivityFailure(endpoint, e)) {
                            continue
                        }
                        Timber.e(e, "[OneClip] Connection loop error")
                        delay(5000)
                    } finally {
                        if (oneClipClient === client) {
                            oneClipClient = null
                        }
                        client.close()
                    }
                }
            }
    }

    private suspend fun checkRemoteClipboard(forceRefresh: Boolean = false) {
        remoteFetchMutex.withLock {
            importedProfileIdsInCurrentSync.clear()
            val endpoint = currentEndpoint()
            ensureEndpointState(endpoint)
            val url = endpoint.address
            val user = currentUsernameForProfile(endpoint.profileKey)
            val pass = currentPasswordForProfile(endpoint.profileKey)
            val backend = endpoint.backend

            if (url.isBlank()) return

            val downloadPath = prefs.getString("clip_sync_download_path", null)
            val downloadUri =
                StoragePathUtils.resolveDownloadUri(
                    displayPath = downloadPath,
                    storedUri = prefs.getString("clip_sync_download_path_uri", null),
                )

            val result =
                SyncClient.fetchClipboard(
                    context = this,
                    serverUrl = url,
                    username = user,
                    pass = pass,
                    backend = backend,
                    lastRevision = if (forceRefresh && backend == ServerBackend.ONECLIP) null else lastRemoteRevision,
                    downloadDirUri = downloadUri,
                    preDownloadFilter = ::shouldAcceptIncomingMetadata,
                )
            noteRemoteSyncSuccess()

            val fetchedItems = result.items
            if (fetchedItems.isEmpty()) {
                result.revision?.let {
                    lastRemoteRevision = it
                    persistRemoteRevision(endpoint, it)
                }
                return
            }
            val acceptedItems =
                buildList {
                    for (data in fetchedItems) {
                        if (!shouldAcceptIncomingClipboard(data)) {
                            Timber.d("[Pull] Incoming clipboard rejected by receive filter: type=%s name=%s", data.type, data.dataName)
                            continue
                        }
                        if (isSuppressedRemoteClipboard(data)) {
                            Timber.d("[Pull] Skipping locally suppressed remote clipboard item: type=%s textLength=%s", data.type, data.text.length)
                            continue
                        }
                        if (data.text.isBlank() && !data.type.equals("Text", ignoreCase = true)) {
                            Timber.d("[Pull] Skipping remote binary clipboard item without a readable local URI: type=%s name=%s", data.type, data.dataName)
                            continue
                        }
                        val profileId = data.id
                        if (profileId.isNotBlank() && profileId in importedProfileIdsInCurrentSync) {
                            Timber.d("[Pull] Skipping duplicate profileId: %s", profileId)
                            continue
                        }
                        if (profileId.isNotBlank()) {
                            importedProfileIdsInCurrentSync.add(profileId)
                        }
                        add(data)
                    }
                }
            if (acceptedItems.isEmpty()) {
                result.revision?.let {
                    lastRemoteRevision = it
                    persistRemoteRevision(endpoint, it)
                }
                return
            }

            // Import every accepted item, for every backend (a34c935：保护保存顺序——
            // 不再只取最后一条，否则已下载的文件会被搁置成孤儿）。Updating the system
            // clipboard is a separate step below and still uses only the newest item.
            val itemsToImport = acceptedItems
            var importedAll = true
            var latestItemAlreadyInHistory = false
            itemsToImport.forEachIndexed { index, data ->
                val remoteText = filteredRemoteText(data)
                Timber.d("[Pull] Processed data: type=%s, textLength=%s", data.type, remoteText.length)
                acknowledgePendingUploads(remoteText)
                if (shouldSkipRemoteImportForLocalEcho(data, remoteText)) {
                    Timber.d("[Pull] Skipping echoed local clipboard item in remote history")
                    if (index == itemsToImport.lastIndex) latestItemAlreadyInHistory = true
                    return@forEachIndexed
                }
                val imported = importRemoteClipboardEntry(data, downloadUri, remoteText)
                if (index == itemsToImport.lastIndex) latestItemAlreadyInHistory = imported
                importedAll = imported && importedAll
            }

            if (endpoint.backend == ServerBackend.SYNCCLIPBOARD && !importedAll) {
                Timber.d("[Pull] Remote history import is not ready yet; keeping SyncClipboard revision unchanged")
                return
            }

            val latestItem = itemsToImport.last()
            val remoteText = filteredRemoteText(latestItem)
            if (
                remoteText.isNotEmpty() &&
                remoteText != lastLocalContent &&
                remoteText != lastRemoteContent &&
                remoteText != lastUploadedContent
            ) {
                Timber.d("[Pull] Remote content changed, updating local")
                lastRemoteContent = remoteText
                lastLocalContent = remoteText
                lastUploadedContent = remoteText
                persistLastSyncedContent(remoteText)
                rememberIgnoredRemoteClipboardContent(remoteText)

                withContext(Dispatchers.Main) {
                    if (latestItem.type.equals("Text", ignoreCase = true)) {
                        updateSystemClipboard(remoteText)
                    } else {
                        updateSystemClipboardWithUri(Uri.parse(remoteText))
                    }
                }
            }

            result.revision?.let {
                lastRemoteRevision = it
                persistRemoteRevision(endpoint, it)
            }
        }
    }

    private fun filteredRemoteText(data: ClipboardData): String = data.text

    /**
     * 远端条目写进本地剪贴板历史。
     *
     * 文本走 [ClipboardHelper.insertFromSync]（与系统剪贴板监听相同的去重语义）；
     * 二进制条目本地历史是纯文本库，只保留在系统剪贴板里（见 importRemoteClipboardEntry
     * 的调用方），这里直接视为已导入。
     */
    private suspend fun importRemoteClipboardEntry(
        data: ClipboardData,
        downloadUri: Uri?,
        filteredText: String,
    ): Boolean {
        val remoteText = filteredText.takeIf { it.isNotBlank() } ?: return false
        grantRemoteClipboardPermissions(remoteText, downloadUri)
        if (!data.type.equals("Text", ignoreCase = true)) {
            return true
        }
        return ClipboardHelper.insertFromSync(
            remoteText,
            data.remoteTimestamp.takeIf { it > 0L } ?: System.currentTimeMillis(),
        ) != null
    }

    private fun grantRemoteClipboardPermissions(
        remoteText: String,
        rootUri: Uri?,
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        remoteText
            .takeIf { it.startsWith("content://") }
            ?.let(Uri::parse)
            ?.let { uri ->
                runCatching {
                    grantUriPermission(packageName, uri, flags)
                }.onFailure { error ->
                    Timber.w(error, "[Pull] Failed to grant remote clipboard URI permission: %s", uri)
                }
            }
        rootUri?.let { uri ->
            runCatching {
                grantUriPermission(packageName, uri, flags)
            }.onFailure { error ->
                Timber.w(error, "[Pull] Failed to grant remote clipboard root permission: %s", uri)
            }
        }
    }

    private fun updateSystemClipboard(text: String) {
        try {
            val clip = ClipData.newPlainText("SyncClipboard", text)
            clipboardManager.setPrimaryClip(clip)
            Timber.d("[Pull] System clipboard updated (Text)")
        } catch (e: Exception) {
            Timber.e(e, "[Pull] Failed to update system clipboard")
        }
    }

    private fun updateSystemClipboardWithUri(uri: Uri) {
        try {
            val clip = ClipData.newUri(contentResolver, "SyncClipboard", uri)
            clipboardManager.setPrimaryClip(clip)
            Timber.d("[Pull] System clipboard updated with URI: %s", uri)
        } catch (e: Exception) {
            Timber.e(e, "[Pull] Failed to update system clipboard with URI")
        }
    }

    private fun handleSystemClipboardChanged() {
        val content = readClipboardContent() ?: return
        handleLocalClipboardUpdate(content, "system-clipboard")
    }

    private fun handleClipCascadeMessage(data: ClipCascadeClipboardData) {
        val normalizedType = data.type.lowercase(Locale.ROOT)
        val payloadFingerprint = buildClipCascadePayloadFingerprint(data)
        Timber.d("[ClipCascade] Received remote payload type=%s size=%s", normalizedType, data.payload.length)
        if (payloadFingerprint.isEmpty() ||
            payloadFingerprint == lastLocalContent ||
            payloadFingerprint == lastRemoteContent ||
            payloadFingerprint == lastUploadedContent
        ) {
            return
        }

        scope.launch {
            when (normalizedType) {
                "text" -> {
                    if (!shouldAcceptIncomingText(data.payload)) {
                        Timber.d("[ClipCascade] Rejected text payload by receive filter")
                        return@launch
                    }
                    val normalized = data.payload
                    if (normalized == lastLocalContent || isClipboardEcho(
                            data.payload, normalized, lastRemoteContent, lastUploadedContent,
                        ) { it }
                    ) {
                        return@launch
                    }
                    acknowledgePendingUploads(normalized)
                    rememberAcceptedRemotePayload(normalized)
                    withContext(Dispatchers.Main) {
                        updateSystemClipboard(normalized)
                    }
                }

                "image" -> handleClipCascadeImage(data, payloadFingerprint)

                "file_eager" -> handleClipCascadeFileEager(data, payloadFingerprint)

                "file_stub" -> {
                    if (!shouldAcceptIncomingBinary(data.filename, null)) {
                        Timber.d("[ClipCascade] Rejected file placeholder by receive filter")
                        return@launch
                    }
                    rememberAcceptedRemotePayload(payloadFingerprint)
                    withContext(Dispatchers.Main) {
                        updateSystemClipboard(buildClipCascadeFileStubSummary(data))
                    }
                }

                else -> Timber.w("[ClipCascade] Ignoring unsupported payload type: %s", data.type)
            }
        }
    }

    private suspend fun handleClipCascadeImage(
        data: ClipCascadeClipboardData,
        payloadFingerprint: String,
    ) {
        val fileName = SyncClient.buildClipCascadeImageFileName(data.filename)
        // Filter on the *estimated* size before decoding. Decoding first meant a STOMP text
        // frame -> String -> JSON -> ByteArray chain peaking at 3-4x the payload, with the size
        // check arriving far too late to prevent it (see A9).
        if (!acceptsClipCascadePayload(fileName, data.payload)) return
        val bytes =
            runCatching { Base64.decode(data.payload, Base64.DEFAULT) }
                .getOrElse { error ->
                    Timber.e(error, "[ClipCascade] Failed to decode image payload")
                    return
                }
        val downloadUri = resolveDownloadUri()
        // Re-check the real size, not just the estimate: the pre-decode filter uses an upper
        // bound, so a payload that decodes to more than the hard cap must still be dropped here.
        if (bytes.size > MAX_INCOMING_BINARY_BYTES) {
            Timber.w("[ClipCascade] Decoded image exceeds the size cap: %s", fileName)
            return
        }
        if (!shouldAcceptIncomingBinary(fileName, bytes.size.toLong())) {
            Timber.d("[ClipCascade] Rejected image payload by receive filter: %s", fileName)
            return
        }
        val mimeType = SyncClient.guessClipCascadeImageMimeType(fileName)
        val savedUri =
            SyncClient.saveIncomingBytes(
                context = this,
                dirUri = downloadUri,
                fileName = fileName,
                bytes = bytes,
                mimeType = mimeType,
            )
        if (savedUri != null) {
            rememberAcceptedRemotePayload(payloadFingerprint)
            withContext(Dispatchers.Main) {
                updateSystemClipboardWithUri(savedUri)
            }
        }
    }

    private suspend fun handleClipCascadeFileEager(
        data: ClipCascadeClipboardData,
        payloadFingerprint: String,
    ) {
        val fileName =
            data.filename
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "ClipCascade-${System.currentTimeMillis()}.bin"
        // See handleClipCascadeImage: filter before decoding (A9).
        if (!acceptsClipCascadePayload(fileName, data.payload)) return
        val bytes =
            runCatching { Base64.decode(data.payload, Base64.DEFAULT) }
                .getOrElse { error ->
                    Timber.e(error, "[ClipCascade] Failed to decode file payload")
                    return
                }
        val downloadUri = resolveDownloadUri()
        if (bytes.size > MAX_INCOMING_BINARY_BYTES) {
            Timber.w("[ClipCascade] Decoded file exceeds the size cap: %s", fileName)
            return
        }
        if (!shouldAcceptIncomingBinary(fileName, bytes.size.toLong())) {
            Timber.d("[ClipCascade] Rejected file payload by receive filter: %s", fileName)
            return
        }
        val savedUri =
            SyncClient.saveIncomingBytes(
                context = this,
                dirUri = downloadUri,
                fileName = fileName,
                bytes = bytes,
            )
        if (savedUri != null) {
            rememberAcceptedRemotePayload(payloadFingerprint)
            withContext(Dispatchers.Main) {
                updateSystemClipboardWithUri(savedUri)
            }
        }
    }

    /**
     * Decide whether a base64 ClipCascade payload is worth decoding.
     *
     * Uses the Base64 decoded-size estimate so both the receive filter and the
     * hard cap are applied before the allocation, not after it (see A9).
     */
    private fun acceptsClipCascadePayload(
        fileName: String,
        payload: String,
    ): Boolean {
        val estimatedBytes = Base64Payload.estimatedDecodedBytes(payload)
        if (estimatedBytes > MAX_INCOMING_BINARY_BYTES) {
            Timber.w(
                "[ClipCascade] Rejected payload of about %sMB (limit %sMB): %s",
                estimatedBytes / (1024 * 1024),
                MAX_INCOMING_BINARY_BYTES / (1024 * 1024),
                fileName,
            )
            return false
        }
        if (!shouldAcceptIncomingBinary(fileName, estimatedBytes)) {
            Timber.d("[ClipCascade] Rejected payload by receive filter before decoding: %s", fileName)
            return false
        }
        return true
    }

    private fun buildClipCascadePayloadFingerprint(data: ClipCascadeClipboardData): String =
        when (data.type.lowercase(Locale.ROOT)) {
            "text" -> data.payload
            else -> "${data.type}:${data.filename.orEmpty()}:${data.payload.hashCode()}"
        }

    private fun buildClipCascadeFileStubSummary(data: ClipCascadeClipboardData): String {
        val firstName =
            data.filename
                ?.takeIf { it.isNotBlank() }
                ?: data.payload.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstName.isNotBlank()) {
            "ClipCascade file placeholder: $firstName"
        } else {
            "ClipCascade file placeholder received"
        }
    }

    private fun resolveDownloadUri(): Uri? {
        val downloadPath = prefs.getString("clip_sync_download_path", null)
        return StoragePathUtils.resolveDownloadUri(
            displayPath = downloadPath,
            storedUri = prefs.getString("clip_sync_download_path_uri", null),
        )
    }

    private fun readClipboardContent(): String? {
        val clip =
            runCatching { clipboardManager.primaryClip }
                .onFailure { error ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastClipboardReadFailureLoggedAt >= 60_000L) {
                        lastClipboardReadFailureLoggedAt = now
                        Timber.w(
                            error,
                            "[Push] Failed to read system clipboard. On Android 10+, non-default-IME " +
                                "background access can be blocked by platform policy.",
                        )
                    }
                }
                .getOrNull()
                ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        item.uri?.toString()?.let { return it }
        item.text?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
        return item.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun registerClipboardListenerIfNeeded() {
        if (clipboardListenerRegistered) return
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        clipboardListenerRegistered = true
    }

    private fun unregisterClipboardListenerIfNeeded() {
        if (!clipboardListenerRegistered) return
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipboardListener) }
        clipboardListenerRegistered = false
    }

    private fun registerPrefsListenerIfNeeded() {
        if (prefsListenerRegistered) return
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        prefsListenerRegistered = true
    }

    private fun unregisterPrefsListenerIfNeeded() {
        if (!prefsListenerRegistered) return
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        prefsListenerRegistered = false
    }

    private fun registerScreenStateReceiverIfNeeded() {
        if (screenStateReceiverRegistered) return
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenStateReceiver, filter)
            }
            screenStateReceiverRegistered = true
        }.onFailure {
            Timber.w(it, "Failed to register screen-state receiver")
        }
    }

    private fun unregisterScreenStateReceiverIfNeeded() {
        if (!screenStateReceiverRegistered) return
        runCatching {
            unregisterReceiver(screenStateReceiver)
        }.onFailure {
            Timber.w(it, "Failed to unregister screen-state receiver")
        }
        screenStateReceiverRegistered = false
    }

    private fun ensureScope() {
        if (!scope.isActive) {
            scope = createScope()
        }
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (networkCallbackRegistered) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request =
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
            networkCallbackRegistered = true
        }.onFailure {
            Timber.w(it, "Failed to register network callback")
        }
    }

    private fun unregisterNetworkCallbackIfNeeded() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }.onFailure {
            Timber.w(it, "Failed to unregister network callback")
        }
        networkCallbackRegistered = false
    }

    private fun handleActionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_RECONNECT_SYNC -> {
                scheduleReconnect("notification-reconnect", immediate = true)
            }

            ACTION_START_SYNC, null -> {
                if (intent?.getBooleanExtra(EXTRA_FORCE_ENABLE_SYNC, false) == true) {
                    prefs
                        .edit()
                        .putBoolean(PREF_QUICK_SYNC, true)
                        .putBoolean(PREF_QUICK_SYNC_UNREACHABLE, false)
                        .apply()
                    QuickSyncTileService.requestTileRefresh(this)
                }
            }

            ACTION_INGEST_CAPTURED_CLIPBOARD -> {
                val content = intent.getStringExtra(EXTRA_CAPTURED_CLIPBOARD_CONTENT).orEmpty()
                if (content.isNotBlank()) {
                    forceUploadClipboard(content, "manual-capture")
                }
            }

            ACTION_SUPPRESS_REMOTE_CLIPBOARD -> {
                val contents = intent.getStringArrayListExtra(EXTRA_SUPPRESSED_REMOTE_ITEMS).orEmpty()
                if (contents.isNotEmpty()) {
                    suppressRemoteClipboardContents(contents)
                }
            }
        }
    }

    private fun scheduleReconnect(
        reason: String,
        immediate: Boolean = false,
    ) {
        if (!shouldRunSyncLoops()) return
        networkReconnectJob?.cancel()
        networkReconnectJob =
            scope.launch {
                if (!immediate) {
                    delay(NETWORK_RECONNECT_DEBOUNCE_MS)
                }
                Timber.d("[Reconnect] Restarting sync runtime: %s", reason)
                activeRuntimeFingerprint = currentRuntimeFingerprint()
                startPeriodicSync()
                startHealthMonitor()
            }
    }

    private fun updateForegroundState() {
        if (!shouldRunInForeground()) {
            stopForegroundState()
            return
        }
        if (foregroundActive) {
            // Already in the foreground: only the notification text can be stale. Re-posting it
            // with notify() rather than calling startForeground() again keeps this off the
            // Android 12+ background-start path.
            refreshForegroundNotification()
            return
        }
        startForegroundCompat()
    }

    /**
     * Foreground state: quick sync enabled **and** the screen interactive.
     *
     * Screen-off releases the foreground state and ACTION_SCREEN_ON re-acquires it: Android 15
     * grants dataSync only about 6 hours per 24, after which it refuses to start one at all.
     */
    private fun shouldRunInForeground(): Boolean =
        prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED) && isScreenInteractive()

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.clip_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.clip_sync_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Must match the manifest declaration, which is dataSync (see C21).
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundActive = true
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Timber.w(e, "[Service] Foreground service start not allowed; pausing sync until explicitly enabled.")
                pauseSyncAndStop()
            } else {
                throw e
            }
            foregroundActive = false
        }
    }

    /**
     * Android 15+ dataSync quota expiry (roughly 6 hours per day).
     *
     * Stop the service and persist the paused state. IME focus and screen broadcasts
     * must not immediately restart it while the quota is still exhausted.
     */
    @RequiresApi(35)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Timber.w("[Service] Foreground dataSync quota expired (startId=%s), pausing sync loops", startId)
        pauseSyncAndStop()
    }

    /**
     * Re-post the foreground notification so its state line matches reality.
     */
    private fun refreshForegroundNotification() {
        if (!foregroundActive) return
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(NOTIFICATION_ID, buildForegroundNotification())
        }.onFailure { Timber.w(it, "[Service] Failed to refresh foreground notification") }
    }

    private fun stopForegroundState() {
        if (!foregroundActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundActive = false
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clipboard_24)
            .setContentTitle(getString(R.string.clip_sync_notification_title))
            .setContentText(buildForegroundNotificationText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?: Intent(),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.R.drawable.ic_popup_sync,
                getString(R.string.clip_sync_notification_reconnect),
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, ClipSyncService::class.java).apply { action = ACTION_RECONNECT_SYNC },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.clip_sync_notification_pause),
                PendingIntent.getService(
                    this,
                    3,
                    Intent(this, ClipSyncService::class.java).apply { action = ACTION_PAUSE_SYNC },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.R.drawable.ic_menu_send,
                getString(R.string.clip_sync_notification_capture),
                PendingIntent.getActivity(
                    this,
                    4,
                    Intent(this, ClipboardCaptureActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun buildForegroundNotificationText(): String {
        val backendLabel =
            when (currentBackend()) {
                ServerBackend.SYNCCLIPBOARD -> getString(R.string.clip_sync_profile_syncclipboard)
                ServerBackend.ONECLIP -> getString(R.string.clip_sync_profile_oneclip)
                ServerBackend.CLIPCASCADE -> getString(R.string.clip_sync_profile_clipcascade)
            }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBatteryOptimization =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager?.isIgnoringBatteryOptimizations(packageName) == true
            } else {
                true
            }
        // Say what is actually happening (see C17).
        val stateLabel =
            when {
                !shouldRunSyncLoops() -> getString(R.string.clip_sync_notification_state_paused)
                currentRuntimeMode() == RuntimeMode.NORMAL -> getString(R.string.clip_sync_notification_state_active)
                else -> getString(R.string.clip_sync_notification_state_idle)
            }
        val base =
            if (ignoringBatteryOptimization) {
                getString(R.string.clip_sync_notification_text, backendLabel)
            } else {
                getString(R.string.clip_sync_notification_text_battery, backendLabel)
            }
        return "$stateLabel · $base"
    }

    private fun noteRemoteSyncSuccess() {
        lastSuccessfulRemoteSyncAt = SystemClock.elapsedRealtime()
        markBackendActivity()
    }

    private fun markBackendActivity() {
        lastBackendActivityAt = SystemClock.elapsedRealtime()
    }

    private fun rememberAcceptedRemotePayload(payloadFingerprint: String) {
        lastRemoteContent = payloadFingerprint
        lastLocalContent = payloadFingerprint
        lastUploadedContent = payloadFingerprint
        persistLastSyncedContent(payloadFingerprint)
        rememberIgnoredRemoteClipboardContent(payloadFingerprint)
        noteRemoteSyncSuccess()
    }

    private fun rememberIgnoredRemoteClipboardContent(content: String) {
        if (content.isBlank()) return
        synchronized(ignoredRemoteClipboardContents) {
            ignoredRemoteClipboardContents.remove(content)
            ignoredRemoteClipboardContents.add(content)
            while (ignoredRemoteClipboardContents.size > 8) {
                val first = ignoredRemoteClipboardContents.firstOrNull() ?: break
                ignoredRemoteClipboardContents.remove(first)
            }
        }
    }

    private fun consumeIgnoredRemoteClipboardContent(content: String): Boolean =
        synchronized(ignoredRemoteClipboardContents) {
            ignoredRemoteClipboardContents.remove(content)
        }

    private fun suppressRemoteClipboardContents(contents: Collection<String>) {
        val normalized =
            contents
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        if (normalized.isEmpty()) return
        synchronized(suppressedRemoteClipboardContents) {
            normalized.forEach { content ->
                suppressedRemoteClipboardContents.remove(content)
                suppressedRemoteClipboardContents.add(content)
            }
            while (suppressedRemoteClipboardContents.size > MAX_SUPPRESSED_REMOTE_ITEMS) {
                val first = suppressedRemoteClipboardContents.firstOrNull() ?: break
                suppressedRemoteClipboardContents.remove(first)
            }
        }
        persistSuppressedRemoteClipboardContents()
    }

    private fun isSuppressedRemoteClipboard(data: ClipboardData): Boolean {
        val remoteText = data.text.trim()
        if (remoteText.isEmpty()) return false
        val normalized = filteredRemoteText(data).trim()
        synchronized(suppressedRemoteClipboardContents) {
            return remoteText in suppressedRemoteClipboardContents || normalized in suppressedRemoteClipboardContents
        }
    }

    private fun disconnectClipCascadeClient() {
        clipCascadeClient?.close()
        clipCascadeClient = null
    }

    private fun disconnectOneClipClient() {
        oneClipClient?.close()
        oneClipClient = null
    }

    private fun ensureSelfStarted() {
        if (selfStarted) return
        runCatching {
            startSyncService(this, "self-start")
            selfStarted = true
            Timber.d("ClipSyncService promoted to started service")
        }.onFailure {
            Timber.w(it, "Failed to self-start ClipSyncService; background sync may depend on IME binding")
        }
    }

    private fun resetRemoteCache() {
        activeEndpointIdentity = null
        lastRemoteRevision = null
        lastRemoteContent = null
    }

    private fun resetFailureState() {
        if (prefs.getBoolean(PREF_QUICK_SYNC_UNREACHABLE, false)) {
            prefs.edit().putBoolean(PREF_QUICK_SYNC_UNREACHABLE, false).apply()
        }
    }

    private fun currentBackend(): ServerBackend =
        ServerBackend.fromProfileType(
            prefs.getString(SERVER_PROFILE_TYPE_KEY, null),
        )

    /**
     * Whether the pull/health loops should run at all.
     *
     * Deliberately does **not** consult [PREF_IME_SYNC_ACTIVE]. That flag only affects how
     * often we poll, via [RuntimeMode.IME_IDLE] (see C17).
     */
    private fun shouldRunSyncLoops(): Boolean {
        val quickSyncEnabled = prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)
        return quickSyncEnabled && isScreenInteractive()
    }

    private fun isImeSyncActive(): Boolean = prefs.getBoolean(PREF_IME_SYNC_ACTIVE, false)

    private fun isScreenInteractive(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

    private fun isPowerSaveEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager.isPowerSaveMode

    private fun currentRuntimeMode(): RuntimeMode =
        when {
            isPowerSaveEnabled() -> RuntimeMode.POWER_SAVE
            !isScreenInteractive() -> RuntimeMode.SCREEN_OFF
            !isImeSyncActive() -> RuntimeMode.IME_IDLE
            else -> RuntimeMode.NORMAL
        }

    private fun currentPollingIntervalSeconds(baseIntervalSeconds: Long): Long =
        when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> baseIntervalSeconds
            RuntimeMode.IME_IDLE -> maxOf(baseIntervalSeconds, IME_IDLE_POLL_INTERVAL_SECONDS)
            RuntimeMode.SCREEN_OFF -> maxOf(baseIntervalSeconds, SCREEN_OFF_POLL_INTERVAL_SECONDS)
            RuntimeMode.POWER_SAVE -> maxOf(baseIntervalSeconds, POWER_SAVE_POLL_INTERVAL_SECONDS)
            RuntimeMode.AGGRESSIVE -> maxOf(baseIntervalSeconds, AGGRESSIVE_POLL_INTERVAL_SECONDS)
        }

    private fun currentHealthCheckDelayMs(): Long =
        when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> EVENT_BACKEND_HEALTH_CHECK_MS
            RuntimeMode.IME_IDLE -> maxOf(EVENT_BACKEND_HEALTH_CHECK_MS, SCREEN_OFF_HEALTH_CHECK_MS)
            RuntimeMode.SCREEN_OFF -> SCREEN_OFF_HEALTH_CHECK_MS
            RuntimeMode.POWER_SAVE -> POWER_SAVE_HEALTH_CHECK_MS
            RuntimeMode.AGGRESSIVE -> AGGRESSIVE_HEALTH_CHECK_MS
        }

    private fun currentFallbackPullDelayMs(): Long =
        when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> EVENT_BACKEND_FALLBACK_PULL_MS
            RuntimeMode.IME_IDLE -> maxOf(EVENT_BACKEND_FALLBACK_PULL_MS, SCREEN_OFF_FALLBACK_PULL_MS)
            RuntimeMode.SCREEN_OFF -> SCREEN_OFF_FALLBACK_PULL_MS
            RuntimeMode.POWER_SAVE -> POWER_SAVE_FALLBACK_PULL_MS
            RuntimeMode.AGGRESSIVE -> AGGRESSIVE_FALLBACK_PULL_MS
        }

    private fun currentStaleReconnectDelayMs(): Long =
        when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> EVENT_BACKEND_STALE_RECONNECT_MS
            RuntimeMode.IME_IDLE -> maxOf(EVENT_BACKEND_STALE_RECONNECT_MS, SCREEN_OFF_STALE_RECONNECT_MS)
            RuntimeMode.SCREEN_OFF -> SCREEN_OFF_STALE_RECONNECT_MS
            RuntimeMode.POWER_SAVE -> POWER_SAVE_STALE_RECONNECT_MS
            RuntimeMode.AGGRESSIVE -> AGGRESSIVE_STALE_RECONNECT_MS
        }

    /**
     * Cached receive filter; cleared by [invalidateReceiveFilterCache].
     *
     * Volatile because the pull loop and the ClipCascade message handler read it from different
     * coroutines while the preference listener clears it on the main thread.
     */
    @Volatile
    private var cachedReceiveFilter: ReceiveFilter? = null

    /**
     * The active receive filter.
     *
     * Cached because this is called once per candidate item, and building it reads four
     * preferences and splits a string with a regex — 50 history entries meant 50 rebuilds
     * (see G3).
     */
    private fun currentReceiveFilter(): ReceiveFilter {
        cachedReceiveFilter?.let { return it }
        val state = SyncFilterPrefs.loadState(prefs)
        val filter =
            if (!state.hasActiveRule) {
                ReceiveFilter(
                    blockedExtensions = emptySet(),
                    minFileSizeBytes = null,
                    maxFileSizeBytes = null,
                    minTextChars = null,
                    maxTextChars = null,
                )
            } else {
                ReceiveFilter(
                    blockedExtensions = state.blockedExtensions,
                    minFileSizeBytes = null,
                    maxFileSizeBytes = state.maxFileSizeBytes,
                    minTextChars = state.minTextChars,
                    maxTextChars = state.maxTextChars,
                ).normalized()
            }
        cachedReceiveFilter = filter
        return filter
    }

    private fun invalidateReceiveFilterCache() {
        cachedReceiveFilter = null
    }

    private fun shouldAcceptIncomingMetadata(data: ClipboardData): Boolean {
        if (isRecentUploadedFileEcho(data)) {
            Timber.d("[Pull] Skipping echoed uploaded file metadata: name=%s", data.dataName)
            return false
        }
        return if (isBinaryClipboardData(data)) {
            shouldAcceptIncomingBinary(
                fileName = inferIncomingFileName(data),
                sizeBytes = data.size.takeIf { it > 0 },
            )
        } else {
            shouldAcceptIncomingText(data.text)
        }
    }

    private fun isRecentUploadedFileEcho(data: ClipboardData): Boolean {
        if (!isBinaryClipboardData(data)) return false
        val fileName = inferIncomingFileName(data)?.takeIf { it.isNotBlank() } ?: return false
        val size = data.size.takeIf { it > 0 }
        pruneRecentUploadedFiles()
        return synchronized(recentUploadedFiles) {
            recentUploadedFiles.any { uploaded ->
                uploaded.fileName == fileName &&
                    (size == null || uploaded.size == size)
            }
        }
    }

    private fun shouldAcceptIncomingClipboard(data: ClipboardData): Boolean =
        if (isBinaryClipboardData(data)) {
            shouldAcceptIncomingBinary(
                fileName = inferIncomingFileName(data),
                sizeBytes = data.size.takeIf { it > 0 },
            )
        } else {
            shouldAcceptIncomingText(data.text)
        }

    private fun shouldAcceptIncomingText(text: String): Boolean {
        val filter = currentReceiveFilter()
        val length = text.codePointCount(0, text.length)
        filter.minTextChars?.let { minChars ->
            if (length < minChars) {
                return false
            }
        }
        filter.maxTextChars?.let { maxChars ->
            if (length > maxChars) {
                return false
            }
        }
        return true
    }

    private fun shouldAcceptIncomingBinary(
        fileName: String?,
        sizeBytes: Long?,
    ): Boolean {
        val filter = currentReceiveFilter()
        val extension =
            fileName
                ?.substringAfterLast('.', "")
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        if (extension.isNotEmpty() && extension in filter.blockedExtensions) {
            return false
        }
        sizeBytes?.takeIf { it > 0 }?.let { actualSize ->
            filter.minFileSizeBytes?.let { minSize ->
                if (actualSize < minSize) {
                    return false
                }
            }
            filter.maxFileSizeBytes?.let { maxSize ->
                if (actualSize > maxSize) {
                    return false
                }
            }
        }
        return true
    }

    private fun isBinaryClipboardData(data: ClipboardData): Boolean =
        !data.type.equals("text", ignoreCase = true) ||
            data.hasData ||
            data.hasImage ||
            data.dataName.isNotBlank()

    private fun inferIncomingFileName(data: ClipboardData): String? {
        if (data.dataName.isNotBlank()) {
            return data.dataName
        }
        if (data.type.equals("text", ignoreCase = true)) {
            return null
        }
        return data.text
            .substringAfterLast('/')
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
    }

    private suspend fun handleConnectivityFailure(
        endpoint: ServerEndpoint,
        cause: Exception,
    ): Boolean {
        Timber.e(cause, "[Pull] Error checking remote clipboard for %s@%s", endpoint.profileKey, endpoint.address)

        if (verifyEndpointRecovered(endpoint)) {
            Timber.d("[Pull] Endpoint recovered during connectivity retry: %s", endpoint.address)
            resetFailureState()
            return true
        }

        if (switchToReachableEndpoint(endpoint)) {
            Timber.w("[Pull] Switched active endpoint away from %s", endpoint.address)
            resetRemoteCache()
            resetFailureState()
            return true
        }

        Timber.e("[Pull] All configured endpoints are unreachable, keeping quick sync enabled but marking it unavailable")
        prefs.edit().putBoolean(PREF_QUICK_SYNC_UNREACHABLE, true).apply()
        stopPeriodicSync()
        return true
    }

    private suspend fun verifyEndpointRecovered(endpoint: ServerEndpoint): Boolean {
        val username = currentUsernameForProfile(endpoint.profileKey)
        val password = currentPasswordForProfile(endpoint.profileKey)

        CONNECTIVITY_RETRY_DELAYS_MS.forEach { delayMs ->
            delay(delayMs)
            val result =
                SyncClient.testConnection(
                    serverUrl = endpoint.address,
                    username = username,
                    pass = password,
                    backend = endpoint.backend,
                )
            if (result.isSuccess) {
                return true
            }
        }
        return false
    }

    private suspend fun switchToReachableEndpoint(failedEndpoint: ServerEndpoint): Boolean {
        val alternatives =
            endpointCandidates()
                .filter { it.identity != failedEndpoint.identity }

        for (candidate in alternatives) {
            val username = currentUsernameForProfile(candidate.profileKey)
            val password = currentPasswordForProfile(candidate.profileKey)
            val result =
                SyncClient.testConnection(
                    serverUrl = candidate.address,
                    username = username,
                    pass = password,
                    backend = candidate.backend,
                )
            if (result.isSuccess) {
                prefs
                    .edit()
                    .putString(SERVER_PROFILE_TYPE_KEY, candidate.profileKey)
                    .putString(SERVER_ADDRESS_KEY, candidate.address)
                    .putString(PREF_USERNAME, username)
                    .putString(PREF_PASSWORD, password)
                    .apply()
                return true
            }
        }
        return false
    }

    private fun currentEndpoint(): ServerEndpoint {
        val profileKey =
            prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
                ?.takeIf { it.isNotBlank() }
                ?: PROFILE_SYNC_CLIPBOARD
        val address =
            prefs.getString(SERVER_ADDRESS_KEY, null)
                ?.trim()
                .orEmpty()
                .ifBlank { storedAddressForProfile(profileKey) }
        return ServerEndpoint(
            profileKey = profileKey,
            address = address,
            backend = ServerBackend.fromProfileType(profileKey),
        )
    }

    private fun endpointCandidates(): List<ServerEndpoint> {
        val current = currentEndpoint()
        val orderedProfiles =
            buildList {
                add(current.profileKey)
                add(PROFILE_SYNC_CLIPBOARD)
                add(PROFILE_ONE_CLIP)
                add(PROFILE_CLIP_CASCADE)
                add(PROFILE_CUSTOM)
            }.distinct()

        return orderedProfiles.mapNotNull { profileKey ->
            val address =
                if (profileKey == current.profileKey) {
                    current.address
                } else {
                    storedAddressForProfile(profileKey)
                }.trim()

            if (address.isBlank()) {
                null
            } else {
                ServerEndpoint(
                    profileKey = profileKey,
                    address = address,
                    backend = ServerBackend.fromProfileType(profileKey),
                )
            }
        }.distinctBy { it.identity }
    }

    private fun storedAddressForProfile(profileKey: String): String {
        val key =
            when (profileKey) {
                PROFILE_SYNC_CLIPBOARD -> SERVER_ADDRESS_SYNC_CLIPBOARD_KEY
                PROFILE_ONE_CLIP -> SERVER_ADDRESS_ONE_CLIP_KEY
                PROFILE_CLIP_CASCADE -> SERVER_ADDRESS_CLIP_CASCADE_KEY
                PROFILE_CUSTOM -> SERVER_ADDRESS_CUSTOM_KEY
                else -> null
            }
        val stored = key?.let { prefs.getString(it, null) }?.trim().orEmpty()
        return stored
    }

    private fun currentUsernameForProfile(profileKey: String): String {
        val key =
            when (profileKey) {
                PROFILE_SYNC_CLIPBOARD -> "clip_sync_username_syncclipboard"
                PROFILE_ONE_CLIP -> "clip_sync_username_oneclip"
                PROFILE_CLIP_CASCADE -> "clip_sync_username_clipcascade"
                PROFILE_CUSTOM -> "clip_sync_username_custom"
                else -> null
            }
        val stored = key?.let { prefs.getString(it, null) }.orEmpty()
        return if (stored.isNotBlank() || profileKey == PROFILE_ONE_CLIP) {
            stored
        } else if (profileKey == PROFILE_CLIP_CASCADE || profileKey == PROFILE_SYNC_CLIPBOARD || profileKey == PROFILE_CUSTOM) {
            "admin"
        } else {
            ""
        }
    }

    private fun currentPasswordForProfile(profileKey: String): String {
        val key =
            when (profileKey) {
                PROFILE_SYNC_CLIPBOARD -> "clip_sync_password_syncclipboard"
                PROFILE_ONE_CLIP -> "clip_sync_password_oneclip"
                PROFILE_CLIP_CASCADE -> "clip_sync_password_clipcascade"
                PROFILE_CUSTOM -> "clip_sync_password_custom"
                else -> null
            }
        val stored = key?.let { prefs.getString(it, null) }.orEmpty()
        return if (stored.isNotBlank() || profileKey == PROFILE_ONE_CLIP) {
            stored
        } else {
            "admin123"
        }
    }

    private data class ServerEndpoint(
        val profileKey: String,
        val address: String,
        val backend: ServerBackend,
    ) {
        val identity: String
            get() = "$profileKey|${address.trim()}"
    }

    @Serializable
    private data class PendingUploadEntry(
        val content: String,
        val enqueuedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class RecentUploadedFile(
        val fileName: String,
        val size: Long,
        val hash: String,
        val uploadedAt: Long = System.currentTimeMillis(),
    )

    private fun loadPersistentSyncState() {
        pendingUploads.clear()
        syncStateStore.read(PREF_PENDING_UPLOADS)
            ?.let { serialized ->
                runCatching {
                    stateJson.decodeFromString<List<PendingUploadEntry>>(serialized)
                }.onSuccess { restored ->
                    pendingUploads += restored
                }.onFailure { error ->
                    Timber.w(error, "[State] Failed to restore pending uploads")
                }
            }

        synchronized(storedRemoteRevisions) { storedRemoteRevisions.clear() }
        syncStateStore.read(PREF_REMOTE_REVISIONS)
            ?.let { serialized ->
                runCatching {
                    stateJson.decodeFromString<Map<String, String>>(serialized)
                }.onSuccess { restored ->
                    synchronized(storedRemoteRevisions) { storedRemoteRevisions.putAll(restored) }
                }.onFailure { error ->
                    Timber.w(error, "[State] Failed to restore remote revisions")
                }
            }

        suppressedRemoteClipboardContents.clear()
        syncStateStore.read(PREF_SUPPRESSED_REMOTE_ITEMS)
            ?.let { serialized ->
                runCatching {
                    stateJson.decodeFromString<List<String>>(serialized)
                }.onSuccess { restored ->
                    restored.forEach { item ->
                        if (item.isNotBlank()) {
                            suppressedRemoteClipboardContents.add(item)
                        }
                    }
                }.onFailure { error ->
                    Timber.w(error, "[State] Failed to restore suppressed remote clipboard items")
                }
            }

        synchronized(recentUploadedFiles) {
            recentUploadedFiles.clear()
            syncStateStore.read(PREF_RECENT_UPLOADED_FILES)
                ?.let { serialized ->
                    runCatching {
                        stateJson.decodeFromString<List<RecentUploadedFile>>(serialized)
                    }.onSuccess { restored ->
                        recentUploadedFiles += restored
                    }.onFailure { error ->
                        Timber.w(error, "[State] Failed to restore recent uploaded files")
                    }
                }
        }
        pruneRecentUploadedFiles()
    }

    private fun persistPendingUploadsLocked() {
        // Only persist entries small enough to be worth restoring; oversized ones stay in
        // memory for this session (see SyncStateStore.MAX_PERSISTED_CONTENT_LENGTH).
        val persistable = pendingUploads.filter { SyncStateStore.isPersistable(it.content) }
        val dropped = pendingUploads.size - persistable.size
        if (dropped > 0) {
            // Say so: these uploads are lost if the process dies before they drain.
            Timber.w("[State] %s pending upload(s) too large to persist", dropped)
        }
        syncStateStore.write(PREF_PENDING_UPLOADS, stateJson.encodeToString(persistable))
    }

    private fun persistRemoteRevisions() {
        // Snapshot under the same lock the mutations use, like the other persist* helpers
        // (see C18). encodeToString iterates the map.
        val snapshot = synchronized(storedRemoteRevisions) { storedRemoteRevisions.toMap() }
        syncStateStore.write(PREF_REMOTE_REVISIONS, stateJson.encodeToString(snapshot))
    }

    private fun persistSuppressedRemoteClipboardContents() {
        // Snapshot inside the lock, filter outside it: the copy is private to this call.
        val snapshot =
            synchronized(suppressedRemoteClipboardContents) {
                suppressedRemoteClipboardContents.toList()
            }
        val persistable = snapshot.filter { SyncStateStore.isPersistable(it) }
        if (snapshot.size > persistable.size) {
            // An unpersisted suppression means that remote item comes back after a restart.
            Timber.w("[State] %s suppressed item(s) too large to persist", snapshot.size - persistable.size)
        }
        syncStateStore.write(
            PREF_SUPPRESSED_REMOTE_ITEMS,
            stateJson.encodeToString(persistable),
        )
    }

    private fun persistRecentUploadedFiles() {
        // Snapshot inside the lock before serializing: this list is mutated from the upload
        // path while the receive path prunes it, and encodeToString iterates it.
        val snapshot = synchronized(recentUploadedFiles) { recentUploadedFiles.toList() }
        syncStateStore.write(PREF_RECENT_UPLOADED_FILES, stateJson.encodeToString(snapshot))
    }

    private fun persistLastSyncedContent(content: String) {
        if (!SyncStateStore.isPersistable(content)) {
            // Too large to keep on disk. Leave the previous value in place instead of deleting
            // it: that value is content this device really did sync, so keeping it still
            // suppresses one round trip, whereas clearing the key left the next start with no
            // baseline at all.
            Timber.w("[State] Last synced content too large to persist (%s chars)", content.length)
            return
        }
        syncStateStore.write(PREF_LAST_SYNCED_CONTENT, content)
    }

    private suspend fun enqueuePendingUpload(content: String): Boolean =
        pendingUploadMutex.withLock {
            if (content.isEmpty()) {
                return@withLock false
            }
            pendingUploads.removeAll { it.content == content }
            pendingUploads += PendingUploadEntry(content = content)
            val overflow = pendingUploads.size - MAX_PENDING_UPLOADS
            if (overflow > 0) {
                repeat(overflow) {
                    pendingUploads.removeAt(0)
                }
            }
            persistPendingUploadsLocked()
            true
        }

    private suspend fun acknowledgePendingUploads(content: String): Boolean {
        if (content.isBlank()) return false
        return pendingUploadMutex.withLock {
            val matchedIndex = pendingUploads.indexOfLast { it.content == content }
            if (matchedIndex < 0) {
                return@withLock false
            }
            repeat(matchedIndex + 1) {
                pendingUploads.removeAt(0)
            }
            persistPendingUploadsLocked()
            true
        }
    }

    private fun shouldSkipRemoteImportForLocalEcho(
        data: ClipboardData,
        remoteText: String,
    ): Boolean {
        if (remoteText.isBlank()) return false
        if (!data.type.equals("Text", ignoreCase = true)) {
            return isRecentUploadedFileEcho(data)
        }
        return remoteText == lastLocalContent || remoteText == lastUploadedContent
    }

    private fun rememberUploadedFile(content: String) {
        val uri =
            content
                .takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?.let(Uri::parse)
                ?: return
        // 只读一遍，同时拿到 size 与 hash：固定缓冲区流式摘要，峰值内存是常量。
        val (size, hash) =
            runCatching {
                contentResolver.openInputStream(uri)?.use { HashUtils.sha256AndSize(it) }
            }.getOrNull() ?: return
        val fileName =
            queryDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: return
        val uploaded =
            RecentUploadedFile(
                fileName = fileName,
                size = size,
                hash = hash,
            )
        synchronized(recentUploadedFiles) {
            recentUploadedFiles.removeAll {
                it.fileName == uploaded.fileName && it.size == uploaded.size && it.hash.equals(uploaded.hash, ignoreCase = true)
            }
            recentUploadedFiles += uploaded
        }
        pruneRecentUploadedFiles()
        persistRecentUploadedFiles()
    }

    private fun pruneRecentUploadedFiles() {
        val cutoff = System.currentTimeMillis() - RECENT_UPLOADED_FILE_TTL_MS
        var changed = false
        synchronized(recentUploadedFiles) {
            changed = recentUploadedFiles.removeAll { it.uploadedAt < cutoff } || changed
            val overflow = recentUploadedFiles.size - MAX_RECENT_UPLOADED_FILES
            if (overflow > 0) {
                repeat(overflow) {
                    recentUploadedFiles.removeAt(0)
                }
                changed = true
            }
        }
        if (changed) {
            persistRecentUploadedFiles()
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull()

    private fun ensureEndpointState(endpoint: ServerEndpoint) {
        if (activeEndpointIdentity == endpoint.identity) {
            return
        }
        activeEndpointIdentity = endpoint.identity
        lastRemoteRevision =
            synchronized(storedRemoteRevisions) {
                storedRemoteRevisions[endpoint.identity]
            }
        lastRemoteContent = null
    }

    private fun persistRemoteRevision(
        endpoint: ServerEndpoint,
        revision: String,
    ) {
        synchronized(storedRemoteRevisions) { storedRemoteRevisions[endpoint.identity] = revision }
        persistRemoteRevisions()
    }

    private suspend fun flushPendingUploads(
        reason: String,
        force: Boolean = false,
    ) {
        if (!force && !prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)) {
            return
        }
        pendingUploadDrainMutex.withLock {
            while (true) {
                val next =
                    pendingUploadMutex.withLock {
                        pendingUploads.firstOrNull()
                    } ?: return

                try {
                    pushClipboardToCloud(next.content)
                    pendingUploadMutex.withLock {
                        if (pendingUploads.firstOrNull() == next) {
                            pendingUploads.removeAt(0)
                        } else {
                            pendingUploads.removeAll { it.content == next.content }
                        }
                        persistPendingUploadsLocked()
                    }
                    Timber.d("[Push] Uploaded queued clipboard item from %s", reason)
                } catch (e: SyncClient.StaleClipboardContentException) {
                    Timber.w(e, "[Push] Dropping stale clipboard item from queue: %s", next.content)
                    pendingUploadMutex.withLock {
                        if (pendingUploads.firstOrNull() == next) {
                            pendingUploads.removeAt(0)
                        } else {
                            pendingUploads.removeAll { it.content == next.content }
                        }
                        persistPendingUploadsLocked()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[Push] Failed to upload queued clipboard item from %s", reason)
                    return
                }
            }
        }
    }

    private suspend fun pushClipboardToCloud(text: String) {
        val endpoint = currentEndpoint()
        val url = endpoint.address
        val user = currentUsernameForProfile(endpoint.profileKey)
        val pass = currentPasswordForProfile(endpoint.profileKey)
        val backend = endpoint.backend

        if (url.isBlank()) {
            throw IOException("Server address is blank")
        }

        if (backend == ServerBackend.CLIPCASCADE) {
            val activeClient = clipCascadeClient
            if (activeClient?.isConnected() == true) {
                val outgoing = SyncClient.buildClipCascadeClipboardData(this@ClipSyncService, text)
                activeClient.sendClipboard(
                    payload = outgoing.payload,
                    type = outgoing.type,
                    filename = outgoing.filename,
                )
            } else {
                SyncClient.putClipboard(this@ClipSyncService, url, user, pass, backend, text)
            }
        } else {
            SyncClient.putClipboard(this@ClipSyncService, url, user, pass, backend, text)
        }
        lastUploadedContent = text
        rememberUploadedFile(text)
        persistLastSyncedContent(text)
        markBackendActivity()
    }

    private fun forceUploadClipboard(
        content: String,
        origin: String,
    ) {
        if (content.isBlank()) return
        clipboardTasks.submit(
            scope,
            prepare = {
                val normalizedContent = content
                currentCoroutineContext().ensureActive()
                enqueuePendingUpload(normalizedContent)
            },
            consume = { queued ->
                if (queued) {
                    Timber.d("[Push] Received explicit upload request from %s", origin)
                    flushPendingUploads("explicit:$origin", force = true)
                }
            },
        )
    }

    private enum class RuntimeMode {
        NORMAL,
        /**
         * Screen on, quick sync on, but no text field focused.
         *
         * Replaces the old behaviour of stopping the loops entirely in this state (see C17).
         */
        IME_IDLE,
        SCREEN_OFF,
        POWER_SAVE,
        AGGRESSIVE,
    }

    private data class ReceiveFilter(
        val blockedExtensions: Set<String>,
        val minFileSizeBytes: Long?,
        val maxFileSizeBytes: Long?,
        val minTextChars: Int?,
        val maxTextChars: Int?,
    ) {
        fun normalized(): ReceiveFilter {
            val normalizedFileBounds = normalizeBounds(minFileSizeBytes, maxFileSizeBytes)
            val normalizedTextBounds = normalizeBounds(minTextChars, maxTextChars)
            return copy(
                minFileSizeBytes = normalizedFileBounds.first,
                maxFileSizeBytes = normalizedFileBounds.second,
                minTextChars = normalizedTextBounds.first,
                maxTextChars = normalizedTextBounds.second,
            )
        }

        private fun normalizeBounds(
            minValue: Long?,
            maxValue: Long?,
        ): Pair<Long?, Long?> =
            when {
                minValue == null -> null to maxValue
                maxValue == null -> minValue to null
                minValue <= maxValue -> minValue to maxValue
                else -> maxValue to minValue
            }

        private fun normalizeBounds(
            minValue: Int?,
            maxValue: Int?,
        ): Pair<Int?, Int?> =
            when {
                minValue == null -> null to maxValue
                maxValue == null -> minValue to null
                minValue <= maxValue -> minValue to maxValue
                else -> maxValue to minValue
            }
    }

    private fun createScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}

/** 前台服务的启停入口：pref 开关、Application 创建、输入框唤起三条路都会调到。 */
object ClipSync {
    private val prefs get() = AppPrefsHolder.clipSync

    // WeakHashSet 只弱引用 listener，必须留在字段上防止被回收（见 PreferenceDelegate 警告）
    @androidx.annotation.Keep
    private val enabledListener =
        com.osfans.trime.data.prefs.PreferenceDelegate.OnChangeListener<Boolean> { _, enabled ->
            val context = com.osfans.trime.TrimeApplication.getInstance()
            if (enabled) {
                startSyncService(context, "pref-enable", forceEnableSync = true)
            } else {
                pauseAndStop(context)
            }
        }

    fun init(context: Context) {
        prefs.enabled.registerOnChangeListener(enabledListener)
        if (prefs.enabled.getValue()) {
            startSyncService(context, "app-init")
        }
    }

    /**
     * 起停前台服务的唯一出口（语义对齐中州鹅 MainService）。
     *
     * - `forceEnableSync`：用户刚打开开关，无论 pref 现值直接强启。
     * - `imeSyncActive`：输入框唤起的补启路径（后台起服务被拒时的重试）。
     * - `stopSyncService` 在常驻同步策略下是 no-op：服务一旦跑起来就常驻，
     *   真正的停止由开关关闭的 `pauseAndStop` 走服务的 PAUSE 分支。
     */
    fun startSyncService(
        context: Context,
        reason: String,
        forceEnableSync: Boolean = false,
        imeSyncActive: Boolean = false,
    ) {
        if (!forceEnableSync && !prefs.enabled.getValue()) return
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, ClipSyncService::class.java).apply {
                    putExtra(ClipSyncService.EXTRA_STARTED_BY, reason)
                    putExtra(ClipSyncService.EXTRA_IME_ACTIVE, imeSyncActive)
                },
            )
        }.onFailure { Timber.w("ClipSync start deferred (%s): %s", reason, it) }
    }

    fun stopSyncService(context: Context) {
        // 常驻同步：输入框关闭不停服务（省电降频由服务内部处理）。
        // 保留此出口以对齐上游调用点语义。
    }

    /**
     * API 31+ 在后台起前台服务会被拒（ForegroundServiceStartNotAllowedException）。
     * 应用刚被系统拉起（比如 IME 重绑）时可能还在后台，失败就等下次输入框唤起再试。
     */
    fun ensureStarted(context: Context) {
        startSyncService(context, "ime-start", imeSyncActive = true)
    }

    /** 输入框关闭：清除「输入法在前台」标记并按需刷新服务（上游 stopSyncService 语义）。 */
    fun onInputViewFinished(context: Context) {
        stopSyncService(context)
    }

    private fun pauseAndStop(context: Context) {
        // 交给服务的 ACTION_PAUSE_SYNC 分支：停循环、清 unreachable 标记并 stopSelf。
        context.startService(
            Intent(context, ClipSyncService::class.java).apply {
                action = "com.osfans.trime.clipsync.action.PAUSE"
            },
        )
    }
}

private object AppPrefsHolder {
    val clipSync: com.osfans.trime.data.prefs.AppPrefs.ClipSync
        get() = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().clipSync
}
