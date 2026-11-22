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
 * - 省电：每帧只是重绘，不起新线程/不轮询；且息屏时显式 pause 动画，亮屏重绘时 resume。
 *   节点中心 x = 12dp(paddingStart) + 40dp/2 = 32dp；中心 y = 行垂直中心。
 * */
class TaskChainDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val appContext = context.applicationContext

    /** 缓存 PowerManager，避免每帧 getSystemService */
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val density = context.resources.displayMetrics.density

    /** 光点能量色：亮青蓝（接近参考 HTML 的荧光青蓝，浅色轨道上也醒目） */
    private val pulseColor = Color.parseColor("#40C4FF")

    /** 轨道列中心 x（12dp paddingStart + 40dp 轨道半宽） */
    private val trackCenterX = 32f * density

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

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (host !== parent) host = parent
        if (powerManager?.isInteractive != false && flowAnimator.isPaused) {
            flowAnimator.resume()
        }
        if (!flowAnimator.isStarted) {
            flowAnimator.start()
        }

        val count = parent.childCount
        if (count < 2) return

        // 画连线
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

        drawFlowPulse(canvas, parent)
    }

    private fun drawFlowPulse(canvas: Canvas, parent: RecyclerView) {
        val count = parent.childCount
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
     * 节点点亮画在 item 之上（onDraw 在子 view 之前绘制，光环会被 34dp 的时钟节点圆底盖住）。
     * */
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (pulseY == Float.MIN_VALUE || parent.childCount == 0) return

        val x = parent.getChildAt(0).left + trackCenterX
        val glowRange = 46f * density
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val nodeY = child.top + child.height / 2f
            val dist = kotlin.math.abs(nodeY - pulseY)
            if (dist >= glowRange) continue

            val intensity = 1f - dist / glowRange
            // 34dp 时钟节点整颗泛青光（图标仍可辨识），外面两层淡青柔光，去掉硬边圆环
            nodeHaloPaint.alpha = (intensity * 0x18).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 25f * density, nodeHaloPaint)
            nodeHaloPaint.alpha = (intensity * 0x2C).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 20f * density, nodeHaloPaint)
            nodeFillPaint.alpha = (intensity * 0x50).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 17f * density, nodeFillPaint)
        }
    }
}
