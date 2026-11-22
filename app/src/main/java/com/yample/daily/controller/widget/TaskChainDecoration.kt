package com.yample.daily.controller.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.PowerManager
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.R

/**
 * 任务列表链式时间轴装饰：节点间上下连线 + 流动脉冲光点。
 *
 * - 连线：从上一个节点的中心画到下一个节点中心，统一描边灰；
 * - 流动脉冲：2 个及以上节点时，亮色光点沿连线从首个节点依次流向下一个，循环往复，带渐隐彗尾；
 * - 驱动：ValueAnimator 每帧用 [RecyclerView.postInvalidateOnAnimation] 触发「仅重绘」。
 *   不能用 invalidateItemDecorations()——它在 RecyclerView 1.4.0 内部调 requestLayout()，
 *   在本列表位于 NestedScrollView + wrap_content 的场景下会每帧重测所有 item，造成布局风暴、动画卡死。
 * - 省电：每帧只是重绘，不起新线程/不轮询。三层暂停策略——
 *   息屏时动画自行 pause（亮屏由 onDraw resume）；切走 tab 由 [pause] 显式暂停（[resume] 恢复）；
 *   视图销毁由 [release] 取消动画并断开 host 引用，避免无限动画泄漏 RecyclerView。
 *
 * 卡片化适配（2026-11-22）：任务行已改成「一任务一卡片」，旧的 40dp 轨道列 / 34dp 圆点已移除。
 * 现在节点改为画在卡片左内侧的 padding 区（x = child.left + 12dp，卡片内容从 16dp 起，不压文字）；
 * 灰线仍画在 item 下层，只在卡片间 12dp 间隙可见；光点 / 节点画在上层，贯穿卡片连续可见。
 * */
class TaskChainDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val appContext = context.applicationContext

    /** 缓存 PowerManager，避免每帧 getSystemService */
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val density = context.resources.displayMetrics.density

    /** 光点能量色：亮青蓝（接近参考 HTML 的荧光青蓝，浅色轨道上也醒目） */
    private val pulseColor = Color.parseColor("#40C4FF")

    /** 卡片左内侧 padding 区的轨道中心 x（内容从 16dp 起，12dp 处不压文字） */
    private val trackCenterX = 12f * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.2f * density
        color = ContextCompat.getColor(context, R.color.md_outlineVariant)
    }

    // 流动脉冲：多层光晕（模拟发光）+ 白色高光核心 + 渐隐彗尾
    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(pulseColor, 0x18)
    }

    private val glowMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(pulseColor, 0x38)
    }

    private val glowInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(pulseColor, 0x66)
    }

    private val flowCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pulseColor
    }

    /** 节点点亮联动：整颗节点泛青光 + 外面两层柔光，无硬边圆环 */
    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pulseColor
    }

    private val nodeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pulseColor
    }

    private var host: RecyclerView? = null

    private var flowProgress = 0f

    /** 当前光点 y 坐标，供 onDrawOver 判断哪些节点被「经过」 */
    private var pulseY = Float.MIN_VALUE

    /** 当前是否处于「可见且应动画」状态；切走 tab 置 false，阻止 onDraw 重新拉起动画 */
    private var active = true

    /** 是否已释放；视图销毁后为 true，此后任何绘制都直接返回，不再重启动画 */
    private var released = false

    private val flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            // 息屏时直接暂停，避免部分机型 vsync 仍在走导致无意义重绘；亮屏由 onDraw resume
            if (powerManager?.isInteractive == false) {
                anim.pause()
                return@addUpdateListener
            }
            flowProgress = anim.animatedValue as Float
            host?.postInvalidateOnAnimation()
        }
    }

    /**
     * 页面切走（hide）时暂停循环动画。
     *
     * 只为省电：hide 后 RecyclerView 已经是 GONE、不再重绘，但无限 ValueAnimator 仍会每帧
     * 调 [RecyclerView.postInvalidateOnAnimation]，属于纯空转。pause 后由 [resume] 显式恢复，
     * 不依赖 onDraw 自动 resume —— 因为淡入淡出过渡期间隐藏视图仍可能被绘制一两帧，自动 resume 会把它又拉起来。
     */
    fun pause() {
        active = false
        if (flowAnimator.isStarted && !flowAnimator.isPaused) flowAnimator.pause()
    }

    /** 页面切回（show）时恢复循环动画 */
    fun resume() {
        if (released) return
        active = true
        if (flowAnimator.isStarted && flowAnimator.isPaused) flowAnimator.resume()
    }

    /**
     * 视图销毁时调用：彻底停掉无限循环动画并断开对 RecyclerView 的引用。
     *
     * 不做这一步的话，动画会一直跑下去（并持有 host → 连带 RecyclerView 与整个 Fragment 视图无法回收）。
     * 本装饰器由 TasksFragment 持有引用，在 onDestroyView 里调用。
     */
    fun release() {
        released = true
        active = false
        if (flowAnimator.isStarted) flowAnimator.cancel()
        // 断开 update listener，避免残留的 lambda 继续持有 host / paints
        flowAnimator.removeAllUpdateListeners()
        host = null
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (released) return
        if (host !== parent) host = parent
        if (active && powerManager?.isInteractive != false && flowAnimator.isPaused) {
            flowAnimator.resume()
        }
        if (active && !flowAnimator.isStarted) {
            flowAnimator.start()
        }

        val count = parent.childCount
        if (count < 2) return

        // 只画连线：灰线画在子 View 之下，卡片不透明 → 只在卡片间隙可见（像卡片之间的连接线）
        for (i in 0 until count - 1) {
            val child = parent.getChildAt(i)
            val next = parent.getChildAt(i + 1)
            val pos = parent.getChildAdapterPosition(child)
            val nextPos = parent.getChildAdapterPosition(next)
            if (pos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue
            if (nextPos != pos + 1) continue

            val x = child.left + trackCenterX
            val startY = child.top + child.height / 2f
            val endY = next.top + next.height / 2f
            canvas.drawLine(x, startY, x, endY, linePaint)
        }
    }

    private fun drawFlowPulse(canvas: Canvas, parent: RecyclerView) {
        val count = parent.childCount
        // 先复位：本次算不出光点时，节点泛光不应沿用上一帧的旧位置
        pulseY = Float.MIN_VALUE
        if (count < 2) return

        val segments = ArrayList<FloatArray>(count - 1)
        var totalLen = 0f
        for (i in 0 until count - 1) {
            val child = parent.getChildAt(i)
            val next = parent.getChildAt(i + 1)
            val pos = parent.getChildAdapterPosition(child)
            val nextPos = parent.getChildAdapterPosition(next)
            if (pos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue
            if (nextPos != pos + 1) continue

            val startY = child.top + child.height / 2f
            val endY = next.top + next.height / 2f
            if (endY <= startY) continue
            segments.add(floatArrayOf(startY, endY))
            totalLen += endY - startY
        }
        if (segments.isEmpty() || totalLen <= 0f) return

        val target = flowProgress * totalLen
        var acc = 0f
        pulseY = segments[0][0]
        for (seg in segments) {
            val segLen = seg[1] - seg[0]
            if (target <= acc + segLen) {
                val frac = if (segLen > 0f) (target - acc) / segLen else 0f
                pulseY = seg[0] + frac * segLen
                break
            }
            acc += segLen
        }

        val x = parent.getChildAt(0).left + trackCenterX

        // 渐隐彗尾：光点运动反方向（上方）画一串透明度/半径递减的圆点，形成「流水」拖尾
        val trailSteps = 14
        val trailLen = 44f * density
        val firstY = segments[0][0]
        for (s in 1..trailSteps) {
            val frac = s.toFloat() / trailSteps
            val back = trailLen * frac
            val y = pulseY - back
            if (y < firstY - 4f * density) break
            val alpha = (1f - frac).let { it * it * 0.5f }
            trailPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            val r = 1.3f * density * (1f - frac * 0.4f)
            canvas.drawCircle(x, y, r, trailPaint)
        }
        // 光点：多层光晕（缩小，避免盖住连线）+ 白色高光核心
        canvas.drawCircle(x, pulseY, 4.5f * density, glowOuterPaint)
        canvas.drawCircle(x, pulseY, 3f * density, glowMidPaint)
        canvas.drawCircle(x, pulseY, 2f * density, glowInnerPaint)
        canvas.drawCircle(x, pulseY, 1.3f * density, flowCorePaint)
    }

    /**
     * 光点 / 彗尾 / 节点泛光全部画在 item 之上。
     *
     * 卡片化后卡片是不透明白底、且横向撑满整行，画在 onDraw（子 View 之下）会被卡片完全盖住，
     * 只在卡片间隙闪一下，凑不成「流水」；所以这里必须叠加在卡片上层。
     * 落点 x = 卡片左内侧 12dp，卡片内容从 16dp 起，不压文字。
     * */
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (released || parent.childCount == 0) return
        drawFlowPulse(canvas, parent)
        drawNodeGlow(canvas, parent)
    }

    /** 节点被光点「经过」时泛青光：小尺寸光晕，不再有旧版 34dp 时钟圆底 */
    private fun drawNodeGlow(canvas: Canvas, parent: RecyclerView) {
        if (pulseY == Float.MIN_VALUE) return

        val x = parent.getChildAt(0).left + trackCenterX
        val glowRange = 32f * density
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val nodeY = child.top + child.height / 2f
            val dist = kotlin.math.abs(nodeY - pulseY)
            if (dist >= glowRange) continue

            val intensity = 1f - dist / glowRange
            // 小光点：两层淡青柔光 + 青色实心，尺寸克制不压卡片内容
            nodeHaloPaint.alpha = (intensity * 0x18).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 9f * density, nodeHaloPaint)
            nodeHaloPaint.alpha = (intensity * 0x30).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 6f * density, nodeHaloPaint)
            nodeFillPaint.alpha = (intensity * 0x60).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 3.5f * density, nodeFillPaint)
        }
    }
}
