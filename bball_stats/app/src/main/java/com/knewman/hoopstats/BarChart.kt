package com.knewman.hoopstats

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/** Simple bar chart with a dashed average line. */
class BarChart(
    ctx: Context,
    private val values: List<Int>,
    private val labels: List<String>,
    barColor: Int,
    lineColor: Int,
    textColor: Int,
    private val average: Double
) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; textSize = 11 * density; textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor; strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val max = maxOf(values.max(), 1)
        val top = 20 * density
        val bottom = height - 20 * density
        val slot = width.toFloat() / values.size
        val radius = 6 * density

        values.forEachIndexed { i, value ->
            val h = (bottom - top) * value / max
            val left = i * slot + slot * 0.18f
            val right = (i + 1) * slot - slot * 0.18f
            val cx = (left + right) / 2
            canvas.drawRoundRect(left, bottom - h, right, bottom, radius, radius, barPaint)
            canvas.drawText(value.toString(), cx, bottom - h - 4 * density, textPaint)
            canvas.drawText(labels[i], cx, height - 4 * density, textPaint)
        }
        val y = bottom - (bottom - top) * (average / max).toFloat()
        canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
    }
}
