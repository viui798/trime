/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.data.voice

import com.osfans.trime.R
import com.osfans.trime.util.appContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * JS 语音插件清单：内置两条 + 用户导入的若干条。
 *
 * 这里是「插件是什么」的唯一权威：设置页渲染的字段、会话下发的设置、设置页列表的条目，
 * 全都从同一份 [Spec] 派生，避免各处各读一套数据。
 *
 * 脚本在**会话启动时**才读（asset 或文件），不进输入法启动热路径。
 */
internal object JsVoiceProviders {

    const val QWEN_ASR_ID = "js:qwen-asr"
    const val DOUBAO_ASR_ID = "js:doubao-asr"
    const val TENCENT_ASR_ID = "js:tencent-asr"
    const val TENCENT_TRANSLATE_ID = "js:tencent-translate"

    /** 脚本正文的来源。内置读 asset，用户导入的读私有目录文件。 */
    internal sealed interface Source {
        class Asset(val path: String) : Source

        class Imported(val file: File) : Source
    }

    internal data class Spec(
        val id: String,
        val label: String,
        val version: String,
        val hosts: Set<String>,
        val sampleRate: Int,
        val source: Source,
        val builtIn: Boolean,
        /** 界面展示的来源：内置是 asset 名，导入是用户选中的文件名。 */
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

    private fun builtInSpecs(): List<Spec> = listOf(
        Spec(
            id = QWEN_ASR_ID,
            label = appContext.getString(R.string.voice_provider_qwen),
            version = "1.6.3",
            hosts = setOf(
                "dashscope.aliyuncs.com",
                "maas.qianwenaiapi.com",
                "cn-beijing.maas.qianwenaiapi.com",
            ),
            sampleRate = 16000,
            source = Source.Asset("voice/qwen-asr.js"),
            builtIn = true,
            originName = "qwen-asr.js",
        ),
        Spec(
            id = DOUBAO_ASR_ID,
            label = appContext.getString(R.string.voice_provider_doubao),
            version = "1.2.0",
            // 火山引擎语音识别只开这两个口子：bigmodel_async（流式）与 bigmodel_nostream（单次）。
            hosts = setOf("openspeech.bytedance.com"),
            sampleRate = 16000,
            source = Source.Asset("voice/doubao-asr.js"),
            builtIn = true,
            originName = "doubao-asr.js",
        ),
        Spec(
            id = TENCENT_ASR_ID,
            label = appContext.getString(R.string.voice_provider_tencent_asr),
            version = "1.0.7",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("voice/tencent-asr.js"),
            builtIn = true,
            originName = "tencent-asr.js",
        ),
        Spec(
            id = TENCENT_TRANSLATE_ID,
            label = appContext.getString(R.string.voice_provider_tencent_translate),
            version = "1.0.0",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("voice/tencent-translate.js"),
            builtIn = true,
            originName = "tencent-translate.js",
        ),
    )

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

    // 用户早先导入的插件可能已被收录为内置。优先用内置实现，
    // 保留导入文件与同一 provider id 下的设置与密钥。
    private fun buildSpecs(): List<Spec> = (builtInSpecs() + importedSpecs()).distinctBy { it.id }

    fun all(): List<Spec> = synchronized(lock) { cached ?: buildSpecs().also { cached = it } }

    fun builtInIds(): Set<String> = builtInSpecs().map { it.id }.toSet()

    fun specOf(pluginId: String): Spec? = all().firstOrNull { it.id == pluginId }

    fun readScript(spec: Spec): String = when (val source = spec.source) {
        is Source.Asset -> appContext.assets.open(source.path).bufferedReader().use { it.readText() }
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
