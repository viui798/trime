/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.data.voice

import com.osfans.trime.util.appContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * JS 语音插件清单：**全部来自用户导入**，APK 里不再内置任何插件。
 *
 * 与 Foxy 的做法一致——语音引擎是可选扩展，用户导入什么就用什么；
 * 系统语音输入法（说点啥、各家语音助手）走 `RecognitionService` 那条路，不在此登记。
 *
 * 这里是「插件是什么」的唯一权威：设置页渲染的字段、会话下发的设置、设置页列表的条目，
 * 全都从同一份 [Spec] 派生，避免各处各读一套数据。
 *
 * 脚本在**会话启动时**才读，不进输入法启动热路径。
 */
internal object JsVoiceProviders {
    /** 脚本正文的来源：用户导入的私有目录文件。 */
    internal sealed interface Source {
        class Imported(val file: File) : Source
    }

    internal data class Spec(
        val id: String,
        val label: String,
        val version: String,
        val hosts: Set<String>,
        val sampleRate: Int,
        val source: Source,
        /** 历史字段：内置插件已移除，现在恒为 false，保留是为了不动调用方。 */
        val builtIn: Boolean,
        /** 界面展示的来源：用户导入时选中的文件名。 */
        val originName: String,
    )

    private val lock = Any()
    private var cached: List<Spec>? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            // flash 模式把整段音频 POST 上去等文本，默认 10s 读超时会在云端慢的时候先失败，
            // 而 Foxy 给 stop 回调的预算是 35s。
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun httpClient(): OkHttpClient = http

    private fun importedSpecs(): List<Spec> = JsVoicePluginStore.list().map { record ->
        Spec(
            id = record.meta.providerId,
            label = record.meta.displayName,
            version = record.meta.version,
            hosts = record.meta.hosts.toSet(),
            sampleRate = record.meta.sampleRate,
            source = Source.Imported(JsVoicePluginStore.scriptFile(record.meta.id)),
            builtIn = false,
            originName = record.sourceName,
        )
    }

    private fun buildSpecs(): List<Spec> = importedSpecs().distinctBy { it.id }

    fun all(): List<Spec> = synchronized(lock) { cached ?: buildSpecs().also { cached = it } }

    fun builtInIds(): Set<String> = emptySet()

    fun specOf(pluginId: String): Spec? = all().firstOrNull { it.id == pluginId }

    fun readScript(spec: Spec): String = when (val source = spec.source) {
        is Source.Imported -> if (source.file.isFile) source.file.readText() else ""
    }

    /** 读取插件声明的设置项；设置页与会话共用这一份声明。 */
    suspend fun describe(
        pluginId: String,
        locale: String = Locale.getDefault().toLanguageTag(),
    ): List<JsVoiceField> {
        val spec = specOf(pluginId) ?: return emptyList()
        return JsVoiceScript.describe(readScript(spec), locale)
    }

    /** 导入/删除插件后重建缓存。 */
    @Synchronized
    fun reload() {
        synchronized(lock) { cached = null }
    }
}
