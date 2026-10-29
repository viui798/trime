/* SPDX-License-Identifier: LGPL-2.1-or-later */
/*
 * 移植自中州鹅 clipboardsync/ScreenshotClipboardWatcher.kt。
 * 差异：本地没有独立的剪贴板历史导入 IPC，截图进系统剪贴板后由
 * ClipboardHelper 的系统剪贴板监听自然收录（文本）/仅进系统剪贴板（图片）。
 */
package com.osfans.trime.data.clipsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ScreenshotClipboardWatcher(
    private val context: Context,
    private val clipboardManager: ClipboardManager,
    private val onScreenshotClipboardUri: (String) -> Unit,
) {
    companion object {
        private const val QUERY_LIMIT = 5
        private const val RETRY_DELAY_MS = 1200L
        private const val POLL_INTERVAL_MS = 5000L
        private const val PREF_HANDLED_SCREENSHOTS = "clip_sync_handled_screenshots"
        private const val MAX_HANDLED_SCREENSHOTS = 256
        private const val SCREENSHOT_RECENT_WINDOW_SECONDS = 120L
        private val SCREENSHOT_MARKERS =
            listOf(
                "screenshots",
                "screenshot",
                "screen_shot",
                "screen-shot",
                "截屏",
                "截图",
            )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREF_HANDLED_SCREENSHOTS, Context.MODE_PRIVATE)
    private var registered = false
    private var startedAtSeconds = 0L
    private val handledScreenshotKeys = linkedSetOf<String>()
    private val inFlightScreenshotKeys = mutableSetOf<String>()
    private var handledKeysLoaded = false
    private var pendingQuery = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The periodic MediaStore poll; cancelled by [stop] (it used to run forever). */
    private var pollJob: Job? = null

    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scheduleQuery()
            }

            override fun onChange(
                selfChange: Boolean,
                uri: Uri?,
            ) {
                scheduleQuery()
            }
        }

    fun start() {
        if (registered) return
        if (!hasImageReadPermission()) {
            Timber.w("Screenshot watcher not started: image read permission is missing")
            return
        }
        startedAtSeconds = System.currentTimeMillis() / 1000
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            registered = true
            Timber.d("Screenshot clipboard watcher registered")
            schedulePeriodicQuery()
        }.onFailure {
            Timber.w(it, "Failed to register screenshot watcher")
        }
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacksAndMessages(null)
        runCatching {
            context.contentResolver.unregisterContentObserver(observer)
        }.onFailure {
            Timber.w(it, "Failed to unregister screenshot watcher")
        }
        registered = false
        pendingQuery = false
        // The poll loop and any in-flight query outlived stop() before (see D12): the scope was
        // never cancelled, so a disabled watcher kept querying MediaStore every 5 seconds.
        pollJob?.cancel()
        pollJob = null
        scope.coroutineContext.cancelChildren()
    }

    /**
     * Query MediaStore shortly after a change notification, retrying once.
     *
     * The query and the bookkeeping happen on [scope] (IO); they used to run on the
     * main-thread Handler, which for a large gallery meant a full-table cursor walk on the UI
     * thread every few seconds.
     */
    private fun scheduleQuery() {
        if (!registered || pendingQuery) return
        pendingQuery = true
        handler.postDelayed(
            {
                pendingQuery = false
                if (!registered) return@postDelayed
                scope.launch {
                    val found = queryLatestScreenshot()?.also { copyScreenshotToClipboard(it) }
                    if (found == null) {
                        delay(RETRY_DELAY_MS)
                        if (!registered) return@launch
                        queryLatestScreenshot()?.let { copyScreenshotToClipboard(it) }
                    }
                }
            },
            500L,
        )
    }

    /**
     * Query once right now, for use when the watcher is (re)started.
     *
     * Screen-off calls [stop], so a screenshot taken while the screen was off produces no
     * observer callback for this instance; the caller issues one compensating query on restart
     * instead of waiting for the periodic poll. Coalesced with any in-flight query by
     * [scheduleQuery], and a no-op when the watcher is not registered.
     */
    fun requestImmediateQuery() {
        if (!registered) return
        scheduleQuery()
    }

    /** Periodic fallback poll; runs entirely on [scope] for the same reason as [scheduleQuery]. */
    private fun schedulePeriodicQuery() {
        pollJob?.cancel()
        pollJob =
            scope.launch {
                while (true) {
                    delay(POLL_INTERVAL_MS)
                    if (!registered) break
                    queryLatestScreenshot()?.let { copyScreenshotToClipboard(it) }
                }
            }
    }

    private fun queryLatestScreenshot(): ScreenshotCandidate? {
        if (!hasImageReadPermission()) return null
        val projection =
            mutableListOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Images.Media.RELATIVE_PATH)
                    add(MediaStore.Images.Media.IS_PENDING)
                }
            }.toTypedArray()
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        return runCatching {
            context.contentResolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder,
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val pathIndex =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        } else {
                            -1
                        }
                    val pendingIndex =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                        } else {
                            -1
                        }

                    var checked = 0
                    while (cursor.moveToNext() && checked < QUERY_LIMIT) {
                        checked += 1
                        if (pendingIndex >= 0 && cursor.getInt(pendingIndex) != 0) continue
                        val mimeType = cursor.getString(mimeIndex).orEmpty()
                        if (!mimeType.startsWith("image/")) continue

                        val dateAdded = cursor.getLong(addedIndex)
                        val dateModified = cursor.getLong(modifiedIndex)
                        val dateTakenSeconds = cursor.getLong(takenIndex).takeIf { it > 0L }?.div(1000L) ?: 0L
                        val eventTime = maxOf(dateAdded, dateModified, dateTakenSeconds)
                        if (eventTime + 3 < startedAtSeconds) continue

                        val displayName = cursor.getString(nameIndex).orEmpty()
                        val bucket = cursor.getString(bucketIndex).orEmpty()
                        val relativePath = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                        val looksLikeScreenshot = looksLikeScreenshot(displayName, bucket, relativePath)
                        if (!looksLikeScreenshot) continue
                        if (eventTime < System.currentTimeMillis() / 1000 - SCREENSHOT_RECENT_WINDOW_SECONDS) continue

                        val mediaId = cursor.getLong(idIndex)
                        val uri =
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                mediaId,
                            )
                        val key = screenshotKey(mediaId, displayName, dateAdded)
                        if (isHandled(key)) continue
                        return@use ScreenshotCandidate(uri, key)
                    }
                    null
                }
        }.onFailure {
            Timber.w(it, "Failed to query latest screenshot")
        }.getOrNull()
    }

    /**
     * Publish [candidate] to the system clipboard.
     *
     * Called from [scope] (IO), so the stream probe and the prefs write happen there; only
     * setPrimaryClip and the host callback are posted back to the main thread.
     */
    private suspend fun copyScreenshotToClipboard(candidate: ScreenshotCandidate) {
        if (!tryClaim(candidate.key)) return
        val uri = candidate.uri
        try {
            context.contentResolver.openInputStream(uri)?.close()
            markHandled(candidate.key)
            withContext(Dispatchers.Main) {
                // Queue the upload and install its echo guard before setPrimaryClip notifies the
                // sync service's independent clipboard listener.
                onScreenshotClipboardUri(uri.toString())
                val clip = ClipData.newUri(context.contentResolver, "Screenshot", uri)
                clipboardManager.setPrimaryClip(clip)
            }
            Timber.d("Copied screenshot URI to clipboard: %s", uri)
        } catch (error: Exception) {
            Timber.w(error, "Failed to copy screenshot URI to clipboard: %s", uri)
        } finally {
            releaseClaim(candidate.key)
        }
    }

    private fun screenshotKey(
        mediaId: Long,
        displayName: String,
        dateAdded: Long,
    ): String = "$mediaId|$displayName|$dateAdded"

    // The three functions below were main-thread-only; the query now runs on [scope], where the
    // change-triggered and periodic paths can overlap. Guard the set and its prefs mirror.
    private fun isHandled(key: String): Boolean =
        synchronized(handledScreenshotKeys) {
            ensureHandledKeysLoadedLocked()
            key in handledScreenshotKeys || key in inFlightScreenshotKeys
        }

    private fun tryClaim(key: String): Boolean =
        synchronized(handledScreenshotKeys) {
            ensureHandledKeysLoadedLocked()
            key !in handledScreenshotKeys && inFlightScreenshotKeys.add(key)
        }

    private fun releaseClaim(key: String) =
        synchronized(handledScreenshotKeys) {
            inFlightScreenshotKeys.remove(key)
        }

    private fun markHandled(key: String) =
        synchronized(handledScreenshotKeys) {
            ensureHandledKeysLoadedLocked()
            inFlightScreenshotKeys.remove(key)
            handledScreenshotKeys.remove(key)
            handledScreenshotKeys.add(key)
            while (handledScreenshotKeys.size > MAX_HANDLED_SCREENSHOTS) {
                val first = handledScreenshotKeys.firstOrNull() ?: break
                handledScreenshotKeys.remove(first)
            }
            prefs
                .edit()
                .putStringSet(PREF_HANDLED_SCREENSHOTS, handledScreenshotKeys.toSet())
                .apply()
        }

    /** Call with the [handledScreenshotKeys] monitor held. */
    private fun ensureHandledKeysLoadedLocked() {
        if (handledKeysLoaded) return
        handledScreenshotKeys.clear()
        handledScreenshotKeys.addAll(prefs.getStringSet(PREF_HANDLED_SCREENSHOTS, emptySet()).orEmpty())
        handledKeysLoaded = true
    }

    private fun looksLikeScreenshot(
        displayName: String,
        bucket: String,
        relativePath: String,
    ): Boolean {
        val haystack = "$displayName/$bucket/$relativePath".lowercase()
        return SCREENSHOT_MARKERS.any { marker -> marker in haystack }
    }

    private fun hasImageReadPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private data class ScreenshotCandidate(
        val uri: Uri,
        val key: String,
    )
}
