/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.ContextThemeWrapper
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxVerticalDecoration
import com.osfans.trime.ime.core.InputView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.max

class CompactCandidateDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val inputView: InputView by instance()
    private val bar: InputBarDelegate by instance()

    private val theme: Theme
        get() = scope.theme

    private val fillStyle by AppPrefs.defaultInstance().keyboard.horizontalCandidateMode

    private val maxSpanCountPref by lazy {
        AppPrefs.defaultInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                maxSpanCount
            } else {
                maxSpanCountLandscape
            }
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 0f

    /**
     * (for [CompactCandidateMode.AUTO_FILL] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false
    private var followLatestText = false

    private val _unrolledCandidateOffset =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(visibleCount: Int) {
        // 芝版同文：展开面板的 offset 语义是「候选条里已经露出来的候选个数」，
        // CandidatesPagingSource 会从这个下标开始加载，所以面板里不会重复出现条上的候选。
        //
        // 不能直接用 childCount / itemCount：Flexbox 是单行 NOWRAP，为了分配 flexGrow
        // 会把**整行所有候选**都 attach 上（包括屏幕外的），childCount 恒等于已加载总数。
        // 所以这里传的是按子 View 位置算出来的「真正进了可视区的个数」。
        _unrolledCandidateOffset.tryEmit(visibleCount)
        // 注意：Rime 在多候选时会把 total 报成 -1（未知），所以「还有没有更多候选」
        // 不能看 total，要看已加载条数是不是都已经露出来了。
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (adapter.itemCount <= visibleCount),
        )
    }

    /**
     * 候选条里「至少露出一点」的候选个数。
     *
     * 单行布局下候选按 x 顺序排列，`getDecoratedLeft` 是相对 RecyclerView 的坐标
     * （已含滚动偏移），左边界小于视口宽度的就算露出。
     */
    private fun visibleCandidateCount(): Int {
        val viewportRight = view.width - view.paddingRight
        if (viewportRight <= 0) return 0
        var count = 0
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            if (layoutManager.getDecoratedLeft(child) < viewportRight) {
                count = maxOf(count, layoutManager.getPosition(child) + 1)
            }
        }
        return count
    }

    /** Restyles the compact list after a scheme switch; visible rows re-bind. */
    fun refreshColors() {
        separatorDrawable.paint.color = scope.colors.candidateSeparatorColor
        adapter.notifyDataSetChanged()
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(scope).apply {
            setOnItemClickListener { _, _, position ->
                rime.launchOnReady { it.selectCandidate(position, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                inputView.showCandidateActionMenu(position, items[position].text, view, global = true)
                true
            }
        }
    }

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    val layoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            init {
                flexWrap = com.google.android.flexbox.FlexWrap.NOWRAP
            }

            /**
             * 芝版同文：**不要**开启触摸横向滚动，否则候选会「有时点不动」。
             *
             * 候选 item 是 [com.osfans.trime.ime.keyboard.GestureFrame]，它自己吃 ACTION_DOWN
             * 并在 ACTION_UP 里派发 CLICK。而 RecyclerView 一旦 canScrollHorizontally = true，
             * 手指按下后有超过 touchSlop 的横向位移就会被它拦截，直接给 item 发 ACTION_CANCEL —
             * 点击就此丢失。用户手指稳就没事，稍微一飘就选不中，所以表现成「有时候」、
             * 而且在桌面模拟器上很难复现。
             *
             * 关掉触摸滚动后，越界候选仍然可以用右侧 ∨ 展开面板查看；
             * 程序化的 scrollBy（跟随高亮项）不受这个开关影响，照样生效。
             */
            override fun canScrollHorizontally(): Boolean = false

            override fun canScrollVertically(): Boolean = false

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.itemCount) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshUnrolled(visibleCandidateCount())
                if (followLatestText) {
                    followLatestText = false
                    view.post {
                        val item = findViewByPosition(adapter.highlightedIdx.coerceAtLeast(0))
                            ?: return@post
                        // Long composing candidates keep their new suffix visible at the right edge.
                        val viewport = view.width - view.paddingLeft - view.paddingRight
                        val delta = if (getDecoratedMeasuredWidth(item) > viewport) {
                            getDecoratedRight(item) - (view.width - view.paddingRight)
                        } else if (getDecoratedLeft(item) < view.paddingLeft) {
                            getDecoratedLeft(item) - view.paddingLeft
                        } else 0
                        if (delta != 0) view.scrollBy(delta, 0)
                    }
                }
            }
        }
    }

    private val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.style.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = scope.colors.candidateSeparatorColor
        }
    }

    val view: RecyclerView by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == CompactCandidateMode.AUTO_FILL) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - separatorDrawable.intrinsicWidth
                }
            }
        }
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                defaultFocusHighlightEnabled = false
            }
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(separatorDrawable))
        }
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val (total, highlighted, candidates) = data

        val maxSpanCount = maxSpanCountPref.getValue()

        when (fillStyle) {
            CompactCandidateMode.NEVER_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }

            CompactCandidateMode.AUTO_FILL -> {
                layoutMinWidth = view.width / maxSpanCount - separatorDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }

            CompactCandidateMode.ALWAYS_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }

        adapter.updateLayoutParams(layoutMinWidth, layoutFlexGrow)
        adapter.updateCandidates(candidates, total, highlighted) {
            followLatestText = true
            layoutManager.scrollToPosition(highlighted.coerceAtLeast(0))
            view.requestLayout()
        }

        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshUnrolled(0)
        }
    }
}
