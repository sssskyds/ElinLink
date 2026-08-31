package com.elin.elinlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A configurable 180-degree analog meter with blue-yellow-red arc and needle. */
class MeterGaugeView(context: Context) : View(context) {

    private var config: GaugeConfig? = null
    private var value: Double = 0.0

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666") }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111") }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444444"); textSize = sp(12f) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111"); textSize = sp(18f); isFakeBoldText = true; textAlign = Paint.Align.CENTER }

    fun configure(c: GaugeConfig) { config = c; requestLayout(); invalidate() }
    fun setValue(v: Double) { value = v; invalidate() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(canvas: Canvas) {
        val c = config ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(16f)
        val cx = w / 2f
        val cy = h - pad - sp(20f)
        val radius = min(w / 2f - pad, cy - pad)
        if (radius <= 0f) return

        val startAngle = 180f   // 9 o'clock
        val sweep = 180f        // over the top to 3 o'clock
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
            val x0 = cx + (rOuter * cos(a)).toFloat()
            val y0 = cy + (rOuter * sin(a)).toFloat()
            val x1 = cx + (rInner * cos(a)).toFloat()
            val y1 = cy + (rInner * sin(a)).toFloat()
            canvas.drawLine(x0, y0, x1, y1, tickPaint)
        }

        val na = Math.toRadians((startAngle + sweep * frac).toDouble())
        val nLen = radius - stroke - dp(6f)
        val nx = cx + (nLen * cos(na)).toFloat()
        val ny = cy + (nLen * sin(na)).toFloat()
        needlePaint.strokeWidth = dp(3f)
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        canvas.drawCircle(cx, cy, dp(5f), needlePaint)

        canvas.drawText(c.title, pad, pad + sp(12f), titlePaint)
        val label = formatValue(value) + if (c.unit.isNotEmpty()) " " + c.unit else ""
        canvas.drawText(label, cx, cy + sp(18f), valuePaint)
    }

    private fun formatValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
}
