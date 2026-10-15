package com.backpack.spotifydivoom

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object DivoomPacketEncoder {

    // Switch backpack channel to custom buffer
    fun switchChannel(channel: Byte = 0x02): ByteArray {
        return wrapPacket(byteArrayOf(0x45, channel))
    }

    // Set brightness (0-100)
    fun setBrightness(percent: Int): ByteArray {
        val lvl = percent.coerceIn(0, 100).toByte()
        return wrapPacket(byteArrayOf(0x74, lvl))
    }

    // Static solid color frame test for 16x16
    fun encodeSolidColor(r: Int, g: Int, b: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        // Divoom 0x44 direct frame header:
        // [0x44, frame_id_lo, frame_id_hi, delay_lo, delay_hi, color_mode (0 = 24-bit RGB)]
        stream.write(0x44)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)

        for (i in 0 until 256) {
            stream.write(r and 0xFF)
            stream.write(g and 0xFF)
            stream.write(b and 0xFF)
        }
        return wrapPacket(stream.toByteArray())
    }

    // Encodes a 16x16 bitmap directly into 24-bit raw RGB stream
    fun encodeImage(original: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(original, 16, 16, false)
        val stream = ByteArrayOutputStream()

        stream.write(0x44)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val pixel = scaled.getPixel(x, y)
                stream.write((pixel shr 16) and 0xFF) // R
                stream.write((pixel shr 8) and 0xFF)  // G
                stream.write(pixel and 0xFF)         // B
            }
        }
        return wrapPacket(stream.toByteArray())
    }

    private fun wrapPacket(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        val checksum = sum and 0xFFFF

        val raw = ByteArrayOutputStream().apply {
            write(0x01) // START
            write(length and 0xFF)
            write((length shr 8) and 0xFF)
            write(payload)
            write(checksum and 0xFF)
            write((checksum shr 8) and 0xFF)
            write(0x02) // END
        }.toByteArray()

        val escaped = ByteArrayOutputStream()
        escaped.write(raw[0].toInt()) // Start delimiter unescaped
        for (i in 1 until raw.size - 1) {
            val b = raw[i].toInt() and 0xFF
            if (b in 0x01..0x03) {
                escaped.write(0x03)
                escaped.write(b xor 0x03)
            } else {
                escaped.write(b)
            }
        }
        escaped.write(raw.last().toInt()) // End delimiter unescaped

        return escaped.toByteArray()
    }
}
