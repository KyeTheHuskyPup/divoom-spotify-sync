package com.backpack.spotifydivoom

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object DivoomPacketEncoder {

    // Command to switch backpack to DIY / Custom client display channel
    fun switchChannelCommand(): ByteArray {
        val payload = byteArrayOf(0x45, 0x05)
        return wrapPacket(payload)
    }

    fun encodeImageToFrame(original: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(original, 16, 16, true)
        val pixelData = ByteArray(16 * 16 * 3)
        var offset = 0

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val pixel = scaled.getPixel(x, y)
                pixelData[offset++] = ((pixel shr 16) and 0xFF).toByte() // R
                pixelData[offset++] = ((pixel shr 8) and 0xFF).toByte()  // G
                pixelData[offset++] = (pixel and 0xFF).toByte()         // B
            }
        }

        // Divoom 16x16 raw frame packet:
        // Command 0x44
        // Total payload size: 5 header bytes + 768 RGB bytes = 773 bytes
        // 0x44, frame_index (0), time_ms_low (0), time_ms_high (0), color_mode (0x00 = 24bit RGB)
        val cmdHeader = byteArrayOf(0x44, 0x00, 0x00, 0x00, 0x00)
        val payload = cmdHeader + pixelData

        return wrapPacket(payload)
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

        // Divoom control character escaping
        val escaped = ByteArrayOutputStream()
        escaped.write(raw[0].toInt()) // Keep start byte 0x01 unchanged
        for (i in 1 until raw.size - 1) {
            val b = raw[i].toInt() and 0xFF
            if (b in 0x01..0x03) {
                escaped.write(0x03)
                escaped.write(b xor 0x03)
            } else {
                escaped.write(b)
            }
        }
        escaped.write(raw.last().toInt()) // Keep end byte 0x02 unchanged

        return escaped.toByteArray()
    }
}
