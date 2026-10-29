/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * 移植自中州鹅 clipboardsync/SyncStateStore.kt（a34c935：同步状态从默认 SharedPreferences
 * 迁到独立文件，避免大载荷撑爆 IME 进程内存）。
 */
package com.osfans.trime.data.clipsync

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-backed storage for the clipboard-sync service's own state.
 *
 * Values are serialized clipboard payloads; keeping them out of the shared preferences file
 * avoids inflating the settings file and, with it, the IME's resident memory, permanently.
 *
 * Each key is a plain file under `filesDir/clipboardsync_state/`, so a large value costs disk
 * rather than the shared-preferences cache.
 */
class SyncStateStore(context: Context) {
    private val dir = File(context.filesDir, DIR_NAME)

    private fun fileFor(key: String) = File(dir, "$key.json")

    /**
     * Values already handed to [write] but possibly not yet on disk.
     *
     * [write] is asynchronous, so this keeps [read] consistent with what the caller last wrote
     * without having to wait for the file. A deletion is represented by [DELETED] rather than by
     * removing the entry: `read` must see the deletion immediately, not fall through to the old
     * file that the queued delete has not reached yet.
     */
    private val pending = ConcurrentHashMap<String, Any>()

    /**
     * Keys with a write queued but not yet performed, mapped to their latest requested value.
     *
     * A `ConcurrentHashMap` rejects null values, so a pending deletion uses the [DELETED] sentinel.
     * Multiple writes for one key collapse into the last value, so a burst of updates costs one
     * pass over the disk instead of one per call.
     */
    private val dirty = ConcurrentHashMap<String, Any>()

    /**
     * True while a drain task is queued or running.
     *
     * Keeps at most one drain in flight; a write that arrives during a drain only updates [dirty]
     * and lets that drain pick the value up.
     * Only touched while holding [handoff].
     */
    private val drainScheduled = AtomicBoolean(false)

    /**
     * Orders the hand-off between [write] and [drain]; **never** held across file IO.
     *
     * Guarding the "publish value + claim/release slot" steps with one monitor removes the
     * visibility gap between a drain that just released its slot and a writer whose claim attempt
     * had already failed.
     */
    private val handoff = Any()

    fun read(key: String): String? {
        when (val overlay = pending[key]) {
            // 删除在落盘前也必须对 read 可见，否则会读到排队删除尚未覆盖的旧文件
            DELETED -> return null
            is String -> return overlay
            else -> Unit // 没有待落盘的写入，去读文件
        }
        return readPersisted(key)
    }

    private fun readPersisted(key: String): String? =
        runCatching {
            val file = fileFor(key)
            if (!file.isFile) null else file.readText().takeIf { it.isNotBlank() }
        }.onFailure { Timber.w(it, "Failed to read sync state: %s", key) }.getOrNull()

    /**
     * Persist [value] for [key], or delete the entry when [value] is null or blank.
     *
     * The file work is handed to [writeExecutor] rather than done inline: callers include
     * onStartCommand and the clipboard listener, i.e. the main thread, and a suppressed-item set
     * can serialize to megabytes. Writes for one key stay ordered because the executor is
     * single-threaded.
     *
     * Writes are coalesced per key (latest wins) before they reach the executor, so repeated
     * updates of the same key cannot queue up a full write each.
     *
     * The write itself goes to a temporary file and renames, so a process death mid-write
     * cannot leave a truncated JSON document that fails to parse on the next start.
     */
    fun write(
        key: String,
        value: String?,
    ) = enqueue(key, value?.takeIf { it.isNotBlank() } ?: DELETED)

    private fun enqueue(
        key: String,
        value: Any,
    ) {
        val claimed =
            synchronized(handoff) {
                pending[key] = value
                // A later write also retries earlier failures, without a tight retry loop on full disks.
                dirty.putAll(pending)
                drainScheduled.compareAndSet(false, true)
            }
        if (claimed) writeExecutor.execute { drain() }
    }

    /**
     * Persist every currently dirty key, then hand the slot over safely.
     *
     * The loop re-snapshots until it observes an empty [dirty]: writes that land while a batch is
     * being written only replace entries in [dirty] (their claim sees the flag held), so they are
     * picked up by the next round instead of being dropped.
     */
    private fun drain() {
        try {
            while (true) {
                val batch: Map<String, Any>
                synchronized(handoff) {
                    if (dirty.isEmpty()) return
                    // 一次性取走全部待落盘项：落盘期间到达的写入留给下一轮
                    batch = HashMap(dirty)
                    dirty.clear()
                }
                batch.forEach { (key, value) -> persist(key, value) }
            }
        } finally {
            // 异常时也必须释放占位，否则后续所有 write 都会因为标志永远为 true 而不再落盘。
            val reschedule =
                synchronized(handoff) {
                    drainScheduled.set(false)
                    // 释放之后再复查一次：在释放之前到达的写入看到 true 不会自行调度，只能由这里接住
                    dirty.isNotEmpty() && drainScheduled.compareAndSet(false, true)
                }
            if (reschedule) writeExecutor.execute { drain() }
        }
    }

    private fun persist(
        key: String,
        value: Any,
    ) {
        runCatching {
            if (value == DELETED) {
                val file = fileFor(key)
                check(!file.exists() || file.delete()) { "Cannot delete sync state" }
            } else {
                dir.mkdirs()
                writeAtomically(fileFor(key), value as String)
            }
        }.onFailure { Timber.w(it, "Failed to write sync state: %s", key) }
            .onSuccess {
                pending.remove(key, value)
            }
    }

    /** 写临时文件后重命名：进程中途被杀不会留下截断的 JSON。 */
    private fun writeAtomically(
        target: File,
        value: String,
    ) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(target)) {
            target.writeText(value)
            tmp.delete()
        }
    }

    companion object {
        private const val DIR_NAME = "clipboardsync_state"

        /**
         * 哨兵：表示某个 key 待落盘的操作是删除。
         *
         * `ConcurrentHashMap` 不接受 null 值，而 `write(key, null)` 语义是删除，因此用这个
         * 单例对象占位，避免用第二个集合同步维护「删除」这一状态。
         */
        private val DELETED = Any()

        /**
         * Single background thread for all state writes.
         *
         * Single-threaded on purpose: writes for the same key must not overtake each other, and
         * the state files are small enough that one thread is never a bottleneck.
         */
        private val writeExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "clipboardsync-state").apply { isDaemon = true }
            }

        /**
         * Upper bound on a single persisted clipboard payload, in characters.
         *
         * Anything larger is kept in memory for the current session but not written to disk:
         * restoring a multi-megabyte clipboard entry after a restart is not worth an unbounded
         * state file. Roughly 64 KB of UTF-16, which covers any realistic text clipboard.
         */
        const val MAX_PERSISTED_CONTENT_LENGTH = 32 * 1024

        /** True when [content] is small enough to be worth persisting. */
        fun isPersistable(content: String): Boolean = content.length <= MAX_PERSISTED_CONTENT_LENGTH
    }
}
