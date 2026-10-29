/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import splitties.systemservices.vibrator

/**
 * 咩版式语音反馈：键盘不出现任何覆盖层，状态变化只靠提示音 + 震动。
 *
 * 音效取自咩版 trime_pro（GPL 同源），五个阶段各一段：
 * 开始录音、说话结束、识别成功、出错、取消。
 */
object VoiceFeedback : VoiceInputManager.Listener {
    private var soundPool: SoundPool? = null
    private val sounds = mutableMapOf<Int, Int>()
    private var appContext: Context? = null

    /** 注册状态监听；重复调用安全。 */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val pool =
            SoundPool.Builder().setMaxStreams(2).build().also { soundPool = it }
        sounds[Sound.START] = pool.load(context, R.raw.speech_recognition_start, 1)
        sounds[Sound.END] = pool.load(context, R.raw.speech_speech_end, 1)
        sounds[Sound.SUCCESS] = pool.load(context, R.raw.speech_recognition_success, 1)
        sounds[Sound.ERROR] = pool.load(context, R.raw.speech_recognition_error, 1)
        sounds[Sound.CANCEL] = pool.load(context, R.raw.speech_recognition_cancel, 1)
        VoiceInputManager.addListener(this)
    }

    private object Sound {
        const val START = 1
        const val END = 2
        const val SUCCESS = 3
        const val ERROR = 4
        const val CANCEL = 5
    }

    private fun play(id: Int) {
        val pool = soundPool ?: return
        val sid = sounds[id] ?: return
        pool.play(sid, 0.5f, 0.5f, 1, 0, 1f)
        vibrate()
    }

    private fun vibrate() {
        val duration =
            (AppPrefs.defaultInstance().keyboard.vibrationDuration.getValue() * 1.5).toInt().coerceAtLeast(10)
        val vib: Vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator ?: vibrator
            } else {
                @Suppress("DEPRECATION")
                appContext?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: vibrator
            }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(duration.toLong())
            }
        }
    }

    override fun onVoiceState(
        state: VoiceInputManager.State,
        previous: VoiceInputManager.State,
    ) {
        when {
            // 录音开始（CONNECTING 是按下后的第一个阶段）
            previous.phase == VoiceInputManager.Phase.IDLE && state.phase == VoiceInputManager.Phase.CONNECTING ->
                play(Sound.START)
            // 松手：进入等待识别结果
            previous.phase == VoiceInputManager.Phase.LISTENING && state.phase == VoiceInputManager.Phase.PROCESSING ->
                play(Sound.END)
            // 会话收尾：从非 IDLE 归位到 IDLE。带 partial 说明出了结果，否则多半是取消
            previous.phase != VoiceInputManager.Phase.IDLE && state.phase == VoiceInputManager.Phase.IDLE ->
                play(if (state.partial.isNotEmpty()) Sound.SUCCESS else Sound.CANCEL)
        }
    }

    override fun onVoiceError(message: String) {
        play(Sound.ERROR)
    }
}
