/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.util.isNightMode
import splitties.dimensions.dp
import kotlin.math.sin

/**
 * 键盘上方的语音状态条。
 *
 * 不用系统 Toast 展示 partial：识别中的文字会持续变化，气泡式的反馈既打断输入又看不见。
 * 这条 bar 只做三件事——显示状态/partial、画音量、提供「取消」和「完成」两个明确出口。
 */
@SuppressLint("ViewConstructor")
class VoiceInputOverlay(
    context: Context,
) : LinearLayout(context),
    VoiceInputManager.Listener {

    private val night = context.resources.configuration.isNightMode()
    private val textColor = if (night) 0xFFE7F0EA.toInt() else 0xFF1B2A22.toInt()
    private val secondaryColor = if (night) 0xFFB8C9BE.toInt() else 0xFF5B6B62.toInt()
    private val accent = ContextCompat.getColor(context, R.color.colorAccent)

    private val micIcon: ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_baseline_mic_24)
        imageTintList = ContextCompat.getColorStateList(context, R.color.colorAccent)
        scaleType = ImageView.ScaleType.CENTER
    }

    private val wave = VoiceWaveView(context).apply {
        barColor = accent
    }

    private val status: TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(textColor)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val progress: ProgressBar = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
    }

    private val cancelButton: ImageButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_close_24)
        imageTintList = ContextCompat.getColorStateList(context, R.color.colorAccent)
        background = null
        contentDescription = context.getString(R.string.voice_cancel)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { VoiceInputManager.cancel() }
    }

    private val doneButton: ImageButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_check_24)
        imageTintList = ContextCompat.getColorStateList(context, R.color.colorAccent)
        background = null
        contentDescription = context.getString(R.string.voice_done)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { VoiceInputManager.stop() }
    }

    private var lastState: VoiceInputManager.State = VoiceInputManager.State()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(6), 0)
        elevation = dp(8).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (night) 0xF224332C.toInt() else 0xF7FFFFFF.toInt())
            setStroke(dp(1), if (night) 0x33FFFFFF else 0x14000000)
        }
        visibility = View.GONE
        alpha = 0f

        addView(micIcon, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(wave, LayoutParams(dp(56), dp(24)).apply { marginEnd = dp(10) })
        addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(progress, LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        addView(cancelButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) })
        addView(doneButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) })
        status.setText(R.string.voice_status_connecting)
    }

    fun attach() {
        VoiceInputManager.addListener(this)
    }

    fun detach() {
        VoiceInputManager.removeListener(this)
    }

    override fun onVoiceState(state: VoiceInputManager.State) {
        wave.level = state.level / 32767f
        if (!state.active) {
            hide()
            return
        }
        show()
        when (state.phase) {
            VoiceInputManager.Phase.CONNECTING -> {
                status.setText(R.string.voice_status_connecting)
                status.setTextColor(secondaryColor)
                progress.visibility = View.VISIBLE
                doneButton.visibility = View.GONE
            }

            VoiceInputManager.Phase.LISTENING -> {
                if (state.partial.isNotBlank()) {
                    status.text = state.partial
                    status.setTextColor(textColor)
                } else {
                    status.setText(R.string.voice_status_listening)
                    status.setTextColor(secondaryColor)
                }
                progress.visibility = View.GONE
                doneButton.visibility = View.VISIBLE
            }

            VoiceInputManager.Phase.PROCESSING -> {
                status.setText(R.string.voice_status_processing)
                status.setTextColor(secondaryColor)
                progress.visibility = View.VISIBLE
                doneButton.visibility = View.GONE
            }

            VoiceInputManager.Phase.IDLE -> hide()
        }
        lastState = state
    }

    override fun onVoiceError(message: String) {
        // 错误由 VoiceInputManager 统一 toast；这里只保证条子及时消失。
        hide()
    }

    private fun show() {
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(120).start()
        }
    }

    private fun hide() {
        if (visibility == View.GONE) return
        animate().alpha(0f).setDuration(120).withEndAction {
            if (!VoiceInputManager.state().active) visibility = View.GONE
        }.start()
    }
}

/** 12 根随音量起伏的竖条，不依赖任何第三方绘图库。 */
@SuppressLint("ViewConstructor")
private class VoiceWaveView(
    context: Context,
) : View(context) {
    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var barColor: Int = Color.GRAY

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val bars = 12
    private var phase = 0f
    private var smoothed = 0f
    private var tickerRunning = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE) {
                tickerRunning = false
                return
            }
            phase += 0.18f
            postInvalidateOnAnimation()
            postOnAnimationDelayed(this, 50L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicker()
    }

    override fun onDetachedFromWindow() {
        tickerRunning = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startTicker() else {
            tickerRunning = false
            removeCallbacks(ticker)
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        postOnAnimation(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        smoothed += (level - smoothed) * 0.25f
        paint.color = barColor
        val gap = width / (bars * 3f)
        val barWidth = gap
        val step = barWidth + gap
        val startX = (width - (bars * barWidth + (bars - 1) * gap)) / 2f
        val centerY = height / 2f
        val minHeight = height * 0.18f
        val maxHeight = height.toFloat()
        for (i in 0 until bars) {
            val waveShape = (sin(phase + i * 0.65f) + 1f) / 2f
            val strength = 0.22f + 0.78f * smoothed
            val h = (minHeight + (maxHeight - minHeight) * strength * (0.35f + 0.65f * waveShape))
                .coerceAtMost(maxHeight)
            val left = startX + i * step
            val top = centerY - h / 2f
            val radius = barWidth / 2f
            canvas.drawRoundRect(left, top, left + barWidth, top + h, radius, radius, paint)
        }
    }
}
