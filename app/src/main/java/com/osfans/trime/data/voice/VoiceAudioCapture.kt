/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import timber.log.Timber
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 麦克风采集：16 kHz / 16 bit / 单声道，100 ms 一块。
 *
 * 取自 fcitx5-android 的 VoiceInputAudioCapture。采集线程只负责读和投递，
 * JS 会话消费由 [VoiceInputManager] 单独调度。
 */
internal class VoiceAudioCapture(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val onPcm: (ByteArray, Int, Int, Long) -> Unit,
    private val onLevel: ((Int) -> Unit)? = null,
    private val onError: (String) -> Unit,
) {
    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start(): Boolean {
        if (!hasPermission(context)) {
            onError(context.getString(R.string.voice_error_no_permission))
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError(context.getString(R.string.voice_error_audio_minbuf, minBuf))
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            onError(context.getString(R.string.voice_error_audio_construct, e.message))
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError(context.getString(R.string.voice_error_audio_not_init))
            return false
        }
        recorder = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            recorder = null
            running = false
            onError(context.getString(R.string.voice_error_audio_start_failed, e.message))
            return false
        }

        // 100 ms chunks ⇒ low latency for partials, modest queue pressure.
        val chunkSamples = sampleRate / 10
        val chunkBytes = chunkSamples * 2
        val buf = ByteArray(chunkBytes)
        val startedAt = System.currentTimeMillis()

        worker = thread(name = "voice-input-capture", isDaemon = true) {
            try {
                while (running && recorder != null) {
                    val read = recorder?.read(buf, 0, chunkBytes) ?: break
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                            Timber.w("AudioRecord.read error=$read")
                            break
                        }
                        continue
                    }
                    // read() blocks, so running may have been cleared while we waited.
                    if (!running) break
                    val pts = System.currentTimeMillis() - startedAt
                    onPcm(buf, 0, read, pts)
                    onLevel?.invoke(rmsOf(buf, read))
                }
            } catch (e: Throwable) {
                Timber.w(e, "voice capture thread crashed")
            }
        }
        Timber.i("VoiceAudioCapture started: sampleRate=$sampleRate minBuf=$minBuf chunkBytes=$chunkBytes")
        return true
    }

    fun stop() {
        running = false
        val rec = recorder
        recorder = null
        runCatching { rec?.stop() }.onFailure { Timber.w(it, "AudioRecord.stop() failed") }
        runCatching { rec?.release() }.onFailure { Timber.w(it, "AudioRecord.release() failed") }
        val thread = worker
        if (thread != null) {
            runCatching { thread.join(300L) }
            if (thread.isAlive) {
                Timber.w("Capture thread still alive after 300ms join")
            } else {
                worker = null
            }
        }
    }

    private fun rmsOf(
        buf: ByteArray,
        len: Int,
    ): Int {
        if (len < 2) return 0
        val count = len / 2
        var sumSq = 0.0
        var i = 0
        while (i < len - 1) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            sumSq += (s * s).toDouble()
            i += 2
        }
        return sqrt(sumSq / count).toInt().coerceIn(0, 32767)
    }
}
