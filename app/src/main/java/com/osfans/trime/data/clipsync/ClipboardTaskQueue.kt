/* SPDX-License-Identifier: LGPL-2.1-or-later */
/*
 * 移植自中州鹅 clipboardsync/ClipboardTaskQueue.kt（a34c935 保存顺序保护）。
 */
package com.osfans.trime.data.clipsync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reserve local copy order on Main; prepare/enqueue and network work both run on IO. */
internal class ClipboardTaskQueue(
    private val admission: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    fun <T> submit(
        scope: CoroutineScope,
        prepare: suspend () -> T,
        consume: suspend (T) -> Unit,
    ): Job =
        scope.launch(admission) {
            val result = mutex.withLock { withContext(worker) { prepare() } }
            // Draining may wait on the network. New copies can already enter the persistent
            // pending queue while that happens; its existing drain mutex handles one upload.
            withContext(worker) { consume(result) }
        }
}
