package com.elin.elinlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Locale
import kotlin.math.max

/** A configurable bar gauge (horizontal or vertical) with blue-yellow-red fill. */
class BarGaugeView(context: Context) : View(context) {

    private var config: GaugeConfig? = null
    private var value: Double = 0.0

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DDDDDD") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#999999") }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444444"); textSize = sp(12f) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111"); textSize = sp(16f); isFakeBoldText = true }

    fun configure(c: GaugeConfig) { config = c; requestLayout(); invalidate() }
    fun setValue(v: Double) { value = v; invalidate() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(canvas: Canvas) {
        val c = config ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f)
        val maxVal = max(c.maxValue, 1e-9)
        val frac = (value / maxVal).coerceIn(0.0, 1.0)
        fillPaint.color = GaugeColors.colorFor(frac)
        tickPaint.strokeWidth = dp(1f)

        // Header: title (left) and value+unit (right)
        canvas.drawText(c.title, pad, pad + sp(12f), titlePaint)
        val label = formatValue(value) + if (c.unit.isNotEmpty()) " " + c.unit else ""
        val lw = valuePaint.measureText(label)
        canvas.drawText(label, w - pad - lw, pad + sp(15f), valuePaint)

        val steps = c.steps.coerceIn(1, 50)

        if (c.orientation == GaugeOrientation.HORIZONTAL) {
            val top = pad + sp(24f)
            val bottom = h - pad - dp(10f)
            val left = pad
            val right = w - pad
            val r = (bottom - top) / 2f
            canvas.drawRoundRect(RectF(left, top, right, bottom), r, r, trackPaint)
            val fillRight = left + (right - left) * frac.toFloat()
            canvas.drawRoundRect(RectF(left, top, max(fillRight, left + 1f), bottom), r, r, fillPaint)
            for (i in 0..steps) {
                val x = left + (right - left) * (i.toFloat() / steps)
                canvas.drawLine(x, bottom + dp(2f), x, bottom + dp(8f), tickPaint)
            }
        } else {
            val left = w / 2f - dp(20f)
            val right = w / 2f + dp(20f)
            val top = pad + sp(26f)
            val bottom = h - pad
            val r = (right - left) / 2f
            canvas.drawRoundRect(RectF(left, top, right, bottom), r, r, trackPaint)
            val fillTop = bottom - (bottom - top) * frac.toFloat()
            canvas.drawRoundRect(RectF(left, fillTop, right, bottom), r, r, fillPaint)
            for (i in 0..steps) {
                val y = bottom - (bottom - top) * (i.toFloat() / steps)
                canvas.drawLine(right + dp(2f), y, right + dp(8f), y, tickPaint)
            }
        }
    }

    private fun formatValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
}
