package com.elin.elinlink

import android.graphics.Color

/** Selectable gauge color palettes (low value -> high value). */
enum class GaugePalette(val display: String) {
    BLUE_RED("Blue \u2192 Red"),
    RED_BLUE("Red \u2192 Blue"),
    GREEN_RED("Green \u2192 Red"),
    RED_GREEN("Red \u2192 Green"),
    GREEN_BLUE("Green \u2192 Blue"),
    BLUE_GREEN("Blue \u2192 Green")
}

/** Maps a 0..1 fraction to a color along the chosen palette (low -> mid -> high). */
object GaugeColors {
    private val BLUE = Color.rgb(33, 99, 255)
    private val YELLOW = Color.rgb(255, 214, 0)
    private val RED = Color.rgb(229, 57, 53)
    private val GREEN = Color.rgb(46, 175, 80)
    private val CYAN = Color.rgb(0, 190, 190)

    private fun stops(p: GaugePalette): Triple<Int, Int, Int> = when (p) {
        GaugePalette.BLUE_RED -> Triple(BLUE, YELLOW, RED)
        GaugePalette.RED_BLUE -> Triple(RED, YELLOW, BLUE)
        GaugePalette.GREEN_RED -> Triple(GREEN, YELLOW, RED)
        GaugePalette.RED_GREEN -> Triple(RED, YELLOW, GREEN)
        GaugePalette.GREEN_BLUE -> Triple(GREEN, CYAN, BLUE)
        GaugePalette.BLUE_GREEN -> Triple(BLUE, CYAN, GREEN)
    }

    fun colorFor(fraction: Double, palette: GaugePalette = GaugePalette.BLUE_RED): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        val (low, mid, high) = stops(palette)
        return if (f <= 0.5) lerp(low, mid, f / 0.5) else lerp(mid, high, (f - 0.5) / 0.5)
    }

    private fun lerp(a: Int, b: Int, tIn: Double): Int {
        val t = tIn.coerceIn(0.0, 1.0)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}
