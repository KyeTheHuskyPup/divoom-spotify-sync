package com.backpack.spotifydivoom

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object DivoomPacketEncoder {

    fun encodeImageToFrame(original: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(original, 16, 16, true)
        val pixelData = ByteArray(16 * 16 * 3)
        var offset = 0

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val pixel = scaled.getPixel(x, y)
                pixelData[offset++] = ((pixel shr 16) and 0xFF).toByte()
                pixelData[offset++] = ((pixel shr 8) and 0xFF).toByte()
                pixelData[offset++] = (pixel and 0xFF).toByte()
            }
        }

        // Static frame display command (0x44)
        // [0x44, FrameID (0x00), Delay_L (0x0A), Delay_H (0x00), ColorMode (0x00)] + 768 bytes RGB
        val cmdHeader = byteArrayOf(0x44, 0x00, 0x0A, 0x00, 0x00)
        val payload = cmdHeader + pixelData

        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        val checksum = sum and 0xFFFF
        val length = payload.size + 2

        val rawPacket = ByteArrayOutputStream().apply {
            write(0x01)
            write(length and 0xFF)
            write((length shr 8) and 0xFF)
            write(payload)
            write(checksum and 0xFF)
            write((checksum shr 8) and 0xFF)
            write(0x02)
        }.toByteArray()

        val escapedPacket = ByteArrayOutputStream()
        escapedPacket.write(rawPacket[0].toInt())
        for (i in 1 until rawPacket.size - 1) {
            val b = rawPacket[i].toInt() and 0xFF
            if (b in 0x01..0x03) {
                escapedPacket.write(0x03)
                escapedPacket.write(b xor 0x03)
            } else {
                escapedPacket.write(b)
            }
        }
        escapedPacket.write(rawPacket.last().toInt())

        return escapedPacket.toByteArray()
    }
}
