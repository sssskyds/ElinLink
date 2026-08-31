package com.elin.elinlink

import org.json.JSONObject
import java.util.UUID

enum class GaugeType { BAR, METER }
enum class GaugeOrientation { HORIZONTAL, VERTICAL }

/** Configuration for a dashboard gauge (bar or analog meter). */
data class GaugeConfig(
    val id: String,
    val type: GaugeType,
    val title: String,
    val unit: String,
    val multiplier: Double,
    val bitStart: Int,
    val bitEnd: Int,
    val orientation: GaugeOrientation,
    val heightDp: Int,
    val steps: Int,
    val palette: GaugePalette
) {
    /** Number of bits selected (1..32). */
    val bitCount: Int
        get() = (maxOf(bitStart, bitEnd) - minOf(bitStart, bitEnd) + 1).coerceIn(1, 32)

    /** Maximum raw value the selected bits can hold. */
    val rawMax: Double
        get() = ((1L shl bitCount) - 1L).toDouble()

    /** Maximum scaled (displayed) value. */
    val maxValue: Double
        get() = rawMax * multiplier

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("title", title)
        put("unit", unit)
        put("multiplier", multiplier)
        put("bitStart", bitStart)
        put("bitEnd", bitEnd)
        put("orientation", orientation.name)
        put("heightDp", heightDp)
        put("steps", steps)
        put("palette", palette.name)
    }

    companion object {
        fun new(
            type: GaugeType,
            title: String,
            unit: String,
            multiplier: Double,
            bitStart: Int,
            bitEnd: Int,
            orientation: GaugeOrientation,
            heightDp: Int,
            steps: Int,
            palette: GaugePalette = GaugePalette.BLUE_RED
        ): GaugeConfig = GaugeConfig(
            UUID.randomUUID().toString(), type, title, unit, multiplier,
            bitStart, bitEnd, orientation, heightDp, steps, palette
        )

        fun fromJson(o: JSONObject): GaugeConfig = GaugeConfig(
            id = o.optString("id", UUID.randomUUID().toString()),
            type = runCatching { GaugeType.valueOf(o.optString("type", "BAR")) }.getOrDefault(GaugeType.BAR),
            title = o.optString("title", ""),
            unit = o.optString("unit", ""),
            multiplier = o.optDouble("multiplier", 1.0),
            bitStart = o.optInt("bitStart", 0),
            bitEnd = o.optInt("bitEnd", 7),
            orientation = runCatching { GaugeOrientation.valueOf(o.optString("orientation", "HORIZONTAL")) }
                .getOrDefault(GaugeOrientation.HORIZONTAL),
            heightDp = o.optInt("heightDp", 120),
            steps = o.optInt("steps", 10),
            palette = runCatching { GaugePalette.valueOf(o.optString("palette", "BLUE_RED")) }
                .getOrDefault(GaugePalette.BLUE_RED)
        )
    }
}
