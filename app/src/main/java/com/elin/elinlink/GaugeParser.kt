package com.elin.elinlink

/** Parses incoming serial text as hex bytes and extracts bit-range values for gauges. */
object GaugeParser {

    /**
     * Parse a line of hex byte tokens delimited by comma or whitespace.
     * Examples: "1A, 2B FF", "0x1A 0x2B", "1a2b" (single token maps to one byte).
     * Returns null if any token is not a valid 0..255 hex byte.
     */
    fun parseHex(line: String): ByteArray? {
        val tokens = line.trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val out = ByteArray(tokens.size)
        for (i in tokens.indices) {
            val t = tokens[i].removePrefix("0x").removePrefix("0X")
            val v = t.toIntOrNull(16) ?: return null
            if (v < 0 || v > 0xFF) return null
            out[i] = v.toByte()
        }
        return out
    }

    /**
     * Extract an unsigned integer from the inclusive bit range bitStart..bitEnd,
     * MSB-first across the byte stream (bit 0 = most-significant bit of byte 0).
     */
    fun extractBits(frame: ByteArray, bitStart: Int, bitEnd: Int): Long {
        val start = minOf(bitStart, bitEnd).coerceAtLeast(0)
        val end = maxOf(bitStart, bitEnd).coerceAtLeast(0)
        var result = 0L
        for (i in start..end) {
            val byteIndex = i / 8
            val bitInByte = 7 - (i % 8)
            val bit = if (byteIndex in frame.indices)
                (frame[byteIndex].toInt() ushr bitInByte) and 1 else 0
            result = (result shl 1) or bit.toLong()
        }
        return result
    }

    /** Scaled display value for a gauge given the latest frame. */
    fun valueFor(frame: ByteArray, config: GaugeConfig): Double =
        extractBits(frame, config.bitStart, config.bitEnd).toDouble() * config.multiplier
}
