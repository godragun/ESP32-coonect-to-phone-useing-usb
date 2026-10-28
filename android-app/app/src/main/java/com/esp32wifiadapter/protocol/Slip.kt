package com.esp32wifiadapter.protocol

/**
 * SLIP framing / deframing (RFC 1055).
 *
 * Wire format:
 *   END(0xC0)  [payload bytes, with END/ESC escaped]  END(0xC0)
 *
 * Payload layout (first byte = packet type):
 *   0x00  Raw IPv4 packet  (data plane)
 *   0x01  Control command  (Android → ESP32, JSON)
 *   0x02  Control event    (ESP32 → Android, JSON)
 */
object Slip {
    const val END: Byte     = 0xC0.toByte()
    const val ESC: Byte     = 0xDB.toByte()
    const val ESC_END: Byte = 0xDC.toByte()
    const val ESC_ESC: Byte = 0xDD.toByte()

    const val TYPE_DATA: Byte = 0x00
    const val TYPE_CMD: Byte  = 0x01
    const val TYPE_RESP: Byte = 0x02

    /** Encode [payload] into a SLIP frame (including surrounding END bytes). */
    fun encode(payload: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(END)
        for (b in payload) {
            when (b) {
                END -> { out.add(ESC); out.add(ESC_END) }
                ESC -> { out.add(ESC); out.add(ESC_ESC) }
                else -> out.add(b)
            }
        }
        out.add(END)
        return out.toByteArray()
    }

    /**
     * Streaming SLIP decoder.
     *
     * Feed incoming bytes one at a time. When [feed] returns a non-null
     * [ByteArray], a complete SLIP frame has been received.
     */
    class Decoder(private val maxFrameSize: Int = 65536) {
        private val buf = ByteArray(maxFrameSize)
        private var pos     = 0
        private var escaped = false

        fun feed(b: Byte): ByteArray? {
            return when (b) {
                END -> {
                    if (pos > 0) {
                        val frame = buf.copyOf(pos)
                        pos = 0
                        escaped = false
                        frame
                    } else null
                }
                ESC -> {
                    escaped = true
                    null
                }
                ESC_END -> {
                    append(if (escaped) END else b)
                    escaped = false
                    null
                }
                ESC_ESC -> {
                    append(if (escaped) ESC else b)
                    escaped = false
                    null
                }
                else -> {
                    escaped = false
                    append(b)
                    null
                }
            }
        }

        fun reset() { pos = 0; escaped = false }

        private fun append(b: Byte) {
            if (pos < maxFrameSize) buf[pos++] = b
            else reset()   // overflow — discard corrupted frame
        }
    }
}
