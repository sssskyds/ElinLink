package com.elin.elinlink

import android.graphics.Color

/** Blue -> Yellow -> Red mapping: low = blue, mid = yellow, high = red. */
object GaugeColors {
    private val LOW = Color.rgb(33, 99, 255)     // blue
    private val MID = Color.rgb(255, 214, 0)     // yellow
    private val HIGH = Color.rgb(229, 57, 53)    // red

    fun colorFor(fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        return if (f <= 0.5) lerp(LOW, MID, f / 0.5)
        else lerp(MID, HIGH, (f - 0.5) / 0.5)
    }

    private fun lerp(a: Int, b: Int, tIn: Double): Int {
        val t = tIn.coerceIn(0.0, 1.0)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}
