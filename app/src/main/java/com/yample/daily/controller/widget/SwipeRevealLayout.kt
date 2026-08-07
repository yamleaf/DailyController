package com.yample.daily.controller.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 左滑滑出右侧操作按钮的容器。
 *
 * 结构约定：第一个子 View 为「操作面板」（右对齐、可点击的按钮），第二个子 View 为「内容层」（上方卡片）。
 * 手指向左滑时内容层向左平移，露出右侧操作面板；松手后按位移判定吸附到「展开」或「收起」。
 * 仅拦截明显的水平手势，垂直手势放行给父级（RecyclerView 滚动）。
 */
class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 内容层（第 2 个子 View） */
    private var contentView: android.view.View? = null

    /** 操作面板（第 1 个子 View），其宽度即最大可滑出距离 */
    private var actionPanel: android.view.View? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startContentX = 0f
    private var dragging = false
    private var intercepted = false

    /** 展开状态变化回调（true=已展开露出按钮） */
    var onStateChange: ((open: Boolean) -> Unit)? = null

    var isOpen: Boolean = false
        private set

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (childCount >= 2) {
            actionPanel = getChildAt(0)
            contentView = getChildAt(1)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 初次布局或方向变化时，让内容层贴齐操作面板宽度（保持展开态或收起态）
        if (isOpen) {
            contentView?.let { it.translationX = -revealWidth() }
        }
    }

    /** 最大可滑出距离 = 操作面板宽度 */
    private fun revealWidth(): Float = (actionPanel?.width ?: 0).toFloat()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (contentView == null || actionPanel == null) return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startContentX = contentView!!.translationX
                dragging = false
                intercepted = false
                // 点击事件若落在内容层，则交给内容层处理（回收/点击逻辑）
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 水平位移显著大于垂直位移才判定为横向滑动手势
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    intercepted = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }

            else -> return false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (contentView == null || actionPanel == null) return super.onTouchEvent(ev)
        if (!dragging) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val rawDx = ev.x - downX
                val max = revealWidth()
                val target = (startContentX + rawDx).coerceIn(-max, 0f)
                contentView!!.translationX = target
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val max = revealWidth()
                val current = contentView!!.translationX
                val shouldOpen = current < -max / 2f
                animateTo(if (shouldOpen) -max else 0f)
                dragging = false
                intercepted = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    /** 展开：内容层滑到最左露出操作按钮 */
    fun open(animate: Boolean = true) {
        val max = revealWidth()
        if (animate) animateTo(-max) else {
            contentView?.translationX = -max
            setOpenState(true)
        }
    }

    /** 收起：内容层滑回原位 */
    fun close(animate: Boolean = true) {
        if (animate) animateTo(0f) else {
            contentView?.translationX = 0f
            setOpenState(false)
        }
    }

    private fun animateTo(targetX: Float) {
        contentView?.let {
            it.animate()
                .translationX(targetX)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { setOpenState(targetX < 0f) }
                .start()
        }
    }

    private fun setOpenState(open: Boolean) {
        val changed = isOpen != open
        isOpen = open
        if (changed) onStateChange?.invoke(open)
    }

    /** 回收视图时复位，防止复用导致残留位移 */
    fun reset() {
        contentView?.translationX = 0f
        isOpen = false
    }
}
