// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import com.osfans.trime.core.RimeApi
import kotlinx.coroutines.CoroutineScope

/**
 * A interface to run different operations on RimeApi
 */
interface RimeSession {
    /**
     * Run an operation immediately
     * The suspended [block] will be executed in caller's thread.
     * Use this function only for non-blocking operations like
     * accessing [RimeApi.messageFlow].
     */
    fun <T> run(block: suspend RimeApi.() -> T): T

    /**
     * 读取引擎缓存字段，不建立协程、不排队、不等待引擎。
     *
     * 只允许访问 [RimeApi] 上的同步缓存属性（`statusCached`、`paging`、`hasMenu`
     * 等），**绝不能**用它调用任何挂起方法或 JNI。
     *
     * 绘制路径必须走这里：`KeyView.onDraw` 每帧都要为每个按键取标签，键盘上
     * 40 多个键各自又有多次状态读取，用 [run] 会退化成每帧创建上百个
     * `runBlocking` 协程，UI 线程直接被拖到 40ms 以上。
     */
    fun <T> peek(block: RimeApi.() -> T): T

    /**
     * Run an operation immediately if rime is at ready state.
     * Otherwise, caller will be suspended until rime is ready and operation is done.
     * The [block] will be executed in caller's thread.
     * Client should use this function in most cases.
     */
    suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T

    /**
     * Run an operation if rime is at ready state.
     * Otherwise, do nothing.
     * The [block] will be executed in executed in thread pool.
     * This function does not block or suspend the caller.
     */
    fun runIfReady(block: suspend RimeApi.() -> Unit)

    val lifecycleScope: CoroutineScope
}
