package com.elin.elinlink

/**
 * Builds the outgoing byte frame from the set of output controls and their
 * current values, and formats it as comma-separated hex bytes such as
 * "0xD0, 0xD1, 0xD2".
 *
 * Bit layout matches [GaugeParser.extractBits]: bit 0 = most-significant bit of
 * byte 0, MSB-first across the stream.
 */
object ControlFrame {

    /** Write [value] into the inclusive bit range [bitStart..bitEnd] of [frame]. */
    fun writeBits(frame: ByteArray, bitStart: Int, bitEnd: Int, value: Long) {
        val start = minOf(bitStart, bitEnd).coerceAtLeast(0)
        val end = maxOf(bitStart, bitEnd).coerceAtLeast(0)
        val count = end - start + 1
        for (i in 0 until count) {
            val bitGlobal = start + i
            val byteIndex = bitGlobal / 8
            if (byteIndex !in frame.indices) continue
            val bitInByte = 7 - (bitGlobal % 8)
            val bit = ((value ushr (count - 1 - i)) and 1L).toInt()
            val mask = 1 shl bitInByte
            val cur = frame[byteIndex].toInt() and 0xFF
            frame[byteIndex] = (if (bit == 1) cur or mask else cur and mask.inv()).toByte()
        }
    }

    /** Number of bytes needed to hold every control's highest selected bit. */
    fun frameSize(controls: List<ControlConfig>): Int {
        var maxBit = -1
        for (c in controls) maxBit = maxOf(maxBit, maxOf(c.bitStart, c.bitEnd))
        return if (maxBit < 0) 0 else (maxBit / 8) + 1
    }

    /** Compose the full outgoing frame from all controls and their values. */
    fun compose(controls: List<ControlConfig>, values: Map<String, Long>): ByteArray {
        val frame = ByteArray(frameSize(controls))
        for (c in controls) writeBits(frame, c.bitStart, c.bitEnd, values[c.id] ?: 0L)
        return frame
    }

    /** Format as "0xD0, 0xD1, 0xD2". */
    fun toHex(frame: ByteArray): String =
        frame.joinToString(", ") { "0x%02X".format(it.toInt() and 0xFF) }
}
