package com.elin.elinlink

import org.json.JSONObject
import java.util.UUID

enum class ControlType { SLIDER, SWITCH }

/**
 * Configuration for an interactive OUTPUT control (slider or switch).
 * The control writes its current value into the inclusive bit range
 * [bitStart..bitEnd] of the outgoing frame, using the same MSB-first bit
 * layout that [GaugeParser.extractBits] reads. This makes sending the exact
 * inverse of receiving.
 */
data class ControlConfig(
    val id: String,
    val type: ControlType,
    val title: String,
    val bitStart: Int,
    val bitEnd: Int,
    val orientation: GaugeOrientation,
    val heightDp: Int,
    val steps: Int
) {
    /** Number of bits selected (1..32). */
    val bitCount: Int
        get() = (maxOf(bitStart, bitEnd) - minOf(bitStart, bitEnd) + 1).coerceIn(1, 32)

    /** Maximum raw value the selected bits can hold (all ones). */
    val rawMax: Long
        get() = (1L shl bitCount) - 1L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("title", title)
        put("bitStart", bitStart)
        put("bitEnd", bitEnd)
        put("orientation", orientation.name)
        put("heightDp", heightDp)
        put("steps", steps)
    }

    companion object {
        fun new(
            type: ControlType,
            title: String,
            bitStart: Int,
            bitEnd: Int,
            orientation: GaugeOrientation,
            heightDp: Int,
            steps: Int
        ): ControlConfig = ControlConfig(
            UUID.randomUUID().toString(), type, title, bitStart, bitEnd, orientation, heightDp, steps
        )

        fun fromJson(o: JSONObject): ControlConfig = ControlConfig(
            id = o.optString("id", UUID.randomUUID().toString()),
            type = runCatching { ControlType.valueOf(o.optString("type", "SLIDER")) }
                .getOrDefault(ControlType.SLIDER),
            title = o.optString("title", ""),
            bitStart = o.optInt("bitStart", 0),
            bitEnd = o.optInt("bitEnd", 7),
            orientation = runCatching { GaugeOrientation.valueOf(o.optString("orientation", "HORIZONTAL")) }
                .getOrDefault(GaugeOrientation.HORIZONTAL),
            heightDp = o.optInt("heightDp", 60),
            steps = o.optInt("steps", 10)
        )
    }
}
