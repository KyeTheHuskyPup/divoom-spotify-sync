package com.backpack.spotifydivoom

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object DivoomPacketEncoder {

    // Verified canonical 0x74 brightness packet:
    // 01 04 00 74 [val] [crc_lo] [crc_hi] 02
    fun setBrightness(percent: Int): ByteArray {
        val lvl = percent.coerceIn(0, 100)
        return makeCanonicalPacket(byteArrayOf(0x74.toByte(), lvl.toByte()))
    }

    // Switch channel (0x45)
    fun switchChannel(channel: Byte): ByteArray {
        return makeCanonicalPacket(byteArrayOf(0x45.toByte(), channel))
    }

    // Marquee text command (0x73) with correct length-inclusive CRC
    fun encodeMarqueeText(text: String): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayOutputStream()
        stream.write(0x73) // Command
        stream.write(0x00) // slot
        stream.write(0xFF) // R
        stream.write(0xB4) // G (amber)
        stream.write(0x00) // B
        stream.write(0x40) // speed
        stream.write(0x00) // direction
        stream.write(0x00) // font
        stream.write(textBytes.size and 0xFF) // length
        stream.write(textBytes)

        return makeCanonicalPacket(stream.toByteArray())
    }

    // True Divoom framing:
    // Start (0x01) + Length (payload + 2) + Payload + CRC (sum of length + payload) + End (0x02)
    fun makeCanonicalPacket(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        val lenLo = (length and 0xFF).toByte()
        val lenHi = ((length shr 8) and 0xFF).toByte()

        var sum = (lenLo.toInt() and 0xFF) + (lenHi.toInt() and 0xFF)
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        val crc = sum and 0xFFFF
        val crcLo = (crc and 0xFF).toByte()
        val crcHi = ((crc shr 8) and 0xFF).toByte()

        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(lenLo.toInt())
        out.write(lenHi.toInt())
        out.write(payload)
        out.write(crcLo.toInt())
        out.write(crcHi.toInt())
        out.write(0x02)

        return out.toByteArray()
    }
}
