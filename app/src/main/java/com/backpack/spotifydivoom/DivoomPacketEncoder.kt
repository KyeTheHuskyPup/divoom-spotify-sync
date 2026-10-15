package com.backpack.spotifydivoom

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object DivoomPacketEncoder {

    // Set Channel (0x03 is dedicated scrolling text / marquee)
    fun switchChannel(channel: Byte = 0x03): ByteArray {
        return makePacket(byteArrayOf(0x45, channel))
    }

    // Set brightness (0-100)
    fun setBrightness(percent: Int): ByteArray {
        val lvl = percent.coerceIn(0, 100).toByte()
        return makePacket(byteArrayOf(0x74, lvl))
    }

    // Divoom 16x16 Native Scrolling Marquee
    // Text must be UTF-16LE encoded with little-endian 16-bit length
    fun encodeMarqueeText(text: String, r: Int = 255, g: Int = 180, b: Int = 0): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_16LE)
        val textLen = textBytes.size

        val stream = ByteArrayOutputStream()
        stream.write(0x73) // Command ID
        stream.write(0x00) // Text Slot
        stream.write(r and 0xFF) // Red
        stream.write(g and 0xFF) // Green
        stream.write(b and 0xFF) // Blue
        stream.write(0x30)       // Speed
        stream.write(0x00)       // Direction (0x00 = Left)
        stream.write(0x00)       // Font index
        // 16-bit little-endian character byte length:
        stream.write(textLen and 0xFF)
        stream.write((textLen shr 8) and 0xFF)
        stream.write(textBytes)

        return makePacket(stream.toByteArray())
    }

    private fun makePacket(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        var crc = 0
        for (b in payload) {
            crc += (b.toInt() and 0xFF)
        }
        crc = crc and 0xFFFF

        val raw = ByteArrayOutputStream()
        raw.write(length and 0xFF)
        raw.write((length shr 8) and 0xFF)
        raw.write(payload)
        raw.write(crc and 0xFF)
        raw.write((crc shr 8) and 0xFF)

        val rawBytes = raw.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0x01) // START

        for (b in rawBytes) {
            val byteVal = b.toInt() and 0xFF
            if (byteVal in 0x01..0x03) {
                out.write(0x03)
                out.write(byteVal xor 0x03)
            } else {
                out.write(byteVal)
            }
        }

        out.write(0x02) // END
        return out.toByteArray()
    }
}
