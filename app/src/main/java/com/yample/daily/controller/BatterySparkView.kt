package com.yample.daily.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * B5：被控端电池曲线 sparkline。纯静态绘制，无后台任务。
 * 数据点来自快照的 runtime.batterySeries（{ts, level}），按时间升序。
 */
class BatterySparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = ArrayList<Pair<Long, Int>>()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(pts: List<Pair<Long, Int>>) {
        points.clear()
        points.addAll(pts)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (points.size < 2) {
            linePaint.color = context.getColor(R.color.md_outlineVariant)
            canvas.drawLine(0f, h / 2f, w, h / 2f, linePaint)
            return
        }
        val minL = points.minOf { it.second }.toFloat()
        val maxL = points.maxOf { it.second }.toFloat()
        val range = (maxL - minL).coerceAtLeast(1f)
        val pad = 10f
        val usableH = h - pad * 2
        val n = points.size
        val xs = points.mapIndexed { i, _ -> if (n == 1) w / 2f else pad + (w - pad * 2) * i / (n - 1) }
        val ys = points.map { pad + usableH * (1f - (it.second - minL) / range) }

        val lastLevel = points.last().second
        val color = when {
            lastLevel < 30 -> context.getColor(R.color.md_error)
            lastLevel < 60 -> context.getColor(R.color.md_tertiary)
            else -> context.getColor(R.color.md_primary)
        }
        linePaint.color = color
        fillPaint.color = color
        fillPaint.alpha = 40
        dotPaint.color = color

        val line = Path()
        line.moveTo(xs.first(), ys.first())
        for (i in 1 until n) line.lineTo(xs[i], ys[i])

        val fill = Path(line)
        fill.lineTo(xs.last(), h)
        fill.lineTo(xs.first(), h)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
        canvas.drawCircle(xs.last(), ys.last(), 4f, dotPaint)
    }
}
