package com.backpack.spotifydivoom

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object DivoomPacketEncoder {

    // Switch backpack channel to Custom 1 (0x04)
    fun switchChannelCommand(): ByteArray {
        return wrapPacket(byteArrayOf(0x45, 0x04))
    }

    // Direct 16x16 image draw command (0x44 direct raw RGB stream)
    fun encodeImageDirectRaw(original: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(original, 16, 16, true)
        val stream = ByteArrayOutputStream()

        // Direct draw header for Divoom-Backpack
        // Command 0x44, frame 0, delay 0, direct RGB mode (0x00)
        stream.write(0x44)
        stream.write(0x00) // frame id low
        stream.write(0x00) // frame id high
        stream.write(0x00) // delay low
        stream.write(0x00) // delay high
        stream.write(0x00) // 24-bit raw RGB mode

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

    // Modern 0x5B direct static image packet
    fun encodeImageSetView(original: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(original, 16, 16, true)
        val stream = ByteArrayOutputStream()
        stream.write(0x5B)
        stream.write(0x01) // 16x16 mode indicator

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val pixel = scaled.getPixel(x, y)
                stream.write((pixel shr 16) and 0xFF)
                stream.write((pixel shr 8) and 0xFF)
                stream.write(pixel and 0xFF)
            }
        }
        return wrapPacket(stream.toByteArray())
    }

    fun wrapPacket(payload: ByteArray): ByteArray {
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
        escaped.write(raw[0].toInt())
        for (i in 1 until raw.size - 1) {
            val b = raw[i].toInt() and 0xFF
            if (b in 0x01..0x03) {
                escaped.write(0x03)
                escaped.write(b xor 0x03)
            } else {
                escaped.write(b)
            }
        }
        escaped.write(raw.last().toInt())

        return escaped.toByteArray()
    }
}
