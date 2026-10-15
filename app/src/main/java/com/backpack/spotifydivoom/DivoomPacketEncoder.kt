package com.backpack.spotifydivoom

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object DivoomPacketEncoder {

    // Switch backpack channel to custom buffer
    fun switchChannel(channel: Byte = 0x02): ByteArray {
        return makePacket(byteArrayOf(0x45, channel))
    }

    // Set brightness (0-100)
    fun setBrightness(percent: Int): ByteArray {
        val lvl = percent.coerceIn(0, 100).toByte()
        return makePacket(byteArrayOf(0x74, lvl))
    }

    // Divoom Built-in Scrolling Text Command (0x73)
    // R, G, B = 255, 180, 0 (classic amber rail indicator color)
    fun encodeMarqueeText(text: String, r: Int = 255, g: Int = 180, b: Int = 0): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayOutputStream()

        stream.write(0x73) // Marquee Command ID
        stream.write(0x00) // Slot ID (0-19)
        stream.write(0x00) // Alignment / Mode
        stream.write(r and 0xFF) // Text Color R
        stream.write(g and 0xFF) // Text Color G
        stream.write(b and 0xFF) // Text Color B
        stream.write(0x40)       // Scroll Speed (0x00 - 0x64)
        stream.write(0x00)       // Direction (0x00 = Left, 0x01 = Right)
        stream.write(0x00)       // Font ID (0 = Standard 5x7)
        stream.write(textBytes.size and 0xFF) // String length (bytes)
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
