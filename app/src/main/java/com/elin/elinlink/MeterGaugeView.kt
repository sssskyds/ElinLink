package com.elin.elinlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Purely graphical 180-degree analog meter with blue-yellow-red arc and needle. */
class MeterGaugeView(context: Context) : View(context) {

    private var config: GaugeConfig? = null
    private var value: Double = 0.0

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666") }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111") }

    fun configure(c: GaugeConfig) { config = c; invalidate() }
    fun setValue(v: Double) { value = v; invalidate() }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val c = config ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(12f)
        val cx = w / 2f
        val cy = h - pad
        val radius = min(w / 2f - pad, cy - pad)
        if (radius <= 0f) return

        val startAngle = 180f
        val sweep = 180f
        val maxVal = max(c.maxValue, 1e-9)
        val frac = (value / maxVal).coerceIn(0.0, 1.0)
        val stroke = dp(14f)
        arcPaint.strokeWidth = stroke
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val segments = 60
        for (i in 0 until segments) {
            val f0 = i.toFloat() / segments
            arcPaint.color = GaugeColors.colorFor(f0.toDouble())
            canvas.drawArc(oval, startAngle + sweep * f0, sweep / segments + 0.6f, false, arcPaint)
        }

        val steps = c.steps.coerceIn(1, 50)
        tickPaint.strokeWidth = dp(1.5f)
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            val a = Math.toRadians((startAngle + sweep * f).toDouble())
            val rOuter = radius - stroke
            val rInner = radius - stroke - dp(8f)
            canvas.drawLine(
                cx + (rOuter * cos(a)).toFloat(), cy + (rOuter * sin(a)).toFloat(),
                cx + (rInner * cos(a)).toFloat(), cy + (rInner * sin(a)).toFloat(), tickPaint
            )
        }

        val na = Math.toRadians((startAngle + sweep * frac).toDouble())
        val nLen = radius - stroke - dp(6f)
        needlePaint.strokeWidth = dp(3f)
        canvas.drawLine(cx, cy, cx + (nLen * cos(na)).toFloat(), cy + (nLen * sin(na)).toFloat(), needlePaint)
        canvas.drawCircle(cx, cy, dp(5f), needlePaint)
    }
}
