/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.isNightMode
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 咩版式按住说话的波形条：录音期间盖在候选栏位置，键盘保持不动。
 *
 * 视觉与咩版一致——通栏键盘底色，正中圆形徽章嵌麦克风图标，两侧对称的
 * 流动波纹；振幅随麦克风 RMS（[VoiceInputManager.State.level]）实时起伏，
 * 静默时仍有轻微摆动，让「正在录音」一眼可辨。
 *
 * 只服务于 hold 会话（长按空格触发）；点按语音键的会话仍走
 * [VoiceInputOverlay] 胶囊条，两者互斥。
 */
@SuppressLint("ViewConstructor")
class VoiceHoldWaveBar(
    context: Context,
) : View(context),
    VoiceInputManager.Listener {
    private var backColor = 0
    private var waveColor = 0
    private var badgeColor = 0
    private var iconColor = 0

    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val mic: Drawable = DrawableCompat.wrap(
        ContextCompat.getDrawable(context, R.drawable.ic_baseline_mic_24)!!.mutate(),
    )

    /** 波形条数；多条不同频率叠出咩版那种「声波纹理」。 */
    private val waveCount = 3

    /** 归一化后的当前振幅驱动量 0..1，向目标平滑逼近。 */
    private var drive = 0f

    /** 各条波形自带的相位偏移，避免三条线同步呼吸。 */
    private val phaseOffsets = FloatArray(waveCount) { it * 1.7f }

    private var phase = 0f
    private var visibleTarget = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            phase += 0.045f
            postInvalidateOnAnimation()
            postOnAnimationDelayed(this, 16L)
        }
    }

    init {
        visibility = GONE
        alpha = 0f
    }

    /**
     * 主题/配色切换后重取颜色；绘制前调用，避免读到过期配色。
     *
     * 颜色经 [ThemeScope.color] 走回退链解析；个别主题把颜色键配成了背景图
     * （如咖啡主题的 keyboard_back_color），直接取会抛 IllegalArgumentException，
     * 所以逐项兜底到日夜两套安全色。
     */
    fun applyThemeColors(scope: ThemeScope) {
        val night = context.resources.configuration.isNightMode()
        fun safeColor(
            key: String,
            light: Int,
            dark: Int,
        ): Int = runCatching { scope.color(key) }.getOrDefault(if (night) dark else light)

        backColor = safeColor("keyboard_back_color", 0xFFECEFF0.toInt(), 0xFF1E2422.toInt())
        waveColor = safeColor("key_text_color", 0xFF3A4042.toInt(), 0xFFE2E7E5.toInt())
        badgeColor = safeColor("key_back_color", 0xFFF7F9F8.toInt(), 0xFF2C3331.toInt())
        iconColor = waveColor
        if (visibility == VISIBLE) invalidate()
    }

    fun attach() {
        VoiceInputManager.addListener(this)
    }

    fun detach() {
        VoiceInputManager.removeListener(this)
    }

    override fun onVoiceState(
        state: VoiceInputManager.State,
        previous: VoiceInputManager.State,
    ) {
        // 只接管咩版式按住说话；点按触发的会话归 VoiceInputOverlay 管。
        val shouldShow = state.hold && state.active
        if (shouldShow != visibleTarget) {
            visibleTarget = shouldShow
            if (shouldShow) show() else hide()
        }
        if (!shouldShow) return
        // RMS 归一后做感知增益：正常说话 RMS 在几百到几千（满量程 32767），
        // 开方再放大才能铺满振幅；松手后 level 归零，波形自然回落。
        val raw = state.level / 32767f
        val target = (sqrt(raw) * 2.2f).coerceIn(0f, 1f)
        drive += (target - drive) * 0.25f
    }

    override fun onVoiceError(message: String) {
        visibleTarget = false
        hide()
    }

    private fun show() {
        drive = 0f
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            animate().alpha(1f).setDuration(120).start()
        }
        postOnAnimation(ticker)
    }

    private fun hide() {
        if (visibility == GONE) return
        animate().alpha(0f).setDuration(120).withEndAction {
            if (!visibleTarget) visibility = GONE
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (backColor != 0) canvas.drawColor(backColor)

        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val maxAmp = h * 0.38f

        // 波纹先画满通栏，徽章最后盖上，边缘自然被圆形截断（咩版同款层次）。
        strokePaint.color = waveColor
        for (i in 0 until waveCount) {
            val idle = 0.16f + 0.05f * i
            val strength = idle + (1f - idle) * drive
            val amp = maxAmp * (0.34f + 0.16f * i) * strength
            val freq = 2.1f + i * 0.85f
            val speed = 1f + i * 0.42f
            strokePaint.strokeWidth = resources.displayMetrics.density * (1.5f + 0.25f * i)
            strokePaint.alpha = ((0.52f + 0.14f * i) * 255f).toInt().coerceAtMost(255)
            val path = Path()
            var first = true
            var x = 0f
            while (x <= w) {
                val t = x / w
                // 两端轻微收束，避免波峰在屏幕边缘被生硬截断
                val envelope = 0.72f + 0.28f * sin(PI.toFloat() * t)
                val y =
                    cy + amp * envelope *
                        sin((2f * PI.toFloat() * freq * t + phase * speed + phaseOffsets[i]))
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                x += 6f
            }
            canvas.drawPath(path, strokePaint)
        }

        // 中央圆形徽章 + 麦克风图标；说话时随音量轻微放大。
        val badgeRadius = h * 0.34f * (1f + 0.06f * drive)
        badgePaint.color = badgeColor
        canvas.drawCircle(w / 2f, cy, badgeRadius, badgePaint)
        val iconSize = (badgeRadius * 1.05f).toInt()
        val left = (w - iconSize) / 2f
        val top = cy - iconSize / 2f
        DrawableCompat.setTint(mic, iconColor)
        mic.setBounds(left.toInt(), top.toInt(), (left + iconSize).toInt(), (top + iconSize).toInt())
        mic.draw(canvas)
    }
}
