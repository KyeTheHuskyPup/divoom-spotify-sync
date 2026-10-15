package com.backpack.spotifydivoom

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object DivoomPacketEncoder {

    // Switch backpack channel to custom buffer (0x45 0x01)
    fun switchChannelCommand(): ByteArray {
        return wrapPacket(byteArrayOf(0x45, 0x01))
    }

    // Animation initialization command (0x49)
    // Informs the display that 1 frame is about to be sent
    fun initAnimationCommand(): ByteArray {
        val payload = byteArrayOf(0x49, 0x01, 0x00, 0x00)
        return wrapPacket(payload)
    }

    // Encodes a 16x16 bitmap into standard Divoom 16x16 frame format (0x44 with palette)
    fun encodeImageToFrame(original: Bitmap): List<ByteArray> {
        val scaled = Bitmap.createScaledBitmap(original, 16, 16, false)
        val paletteList = mutableListOf<Int>()
        val pixelIndices = IntArray(256)

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val color = scaled.getPixel(x, y) and 0x00FFFFFF
                var index = paletteList.indexOf(color)
                if (index == -1) {
                    if (paletteList.size < 256) {
                        paletteList.add(color)
                        index = paletteList.size - 1
                    } else {
                        index = 0
                    }
                }
                pixelIndices[y * 16 + x] = index
            }
        }

        val paletteSize = paletteList.size
        val bitsPerPixel = when {
            paletteSize <= 2 -> 1
            paletteSize <= 4 -> 2
            paletteSize <= 8 -> 3
            paletteSize <= 16 -> 4
            paletteSize <= 32 -> 5
            paletteSize <= 64 -> 6
            paletteSize <= 128 -> 7
            else -> 8
        }

        val frameDataStream = ByteArrayOutputStream()

        // Palette data (RGB 3 bytes per color)
        for (color in paletteList) {
            frameDataStream.write((color shr 16) and 0xFF)
            frameDataStream.write((color shr 8) and 0xFF)
            frameDataStream.write(color and 0xFF)
        }

        // Pack pixel bit indices
        var bitBuffer = 0
        var bitCount = 0
        for (idx in pixelIndices) {
            bitBuffer = bitBuffer or (idx shl bitCount)
            bitCount += bitsPerPixel
            while (bitCount >= 8) {
                frameDataStream.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }
        if (bitCount > 0) {
            frameDataStream.write(bitBuffer and 0xFF)
        }

        val frameBytes = frameDataStream.toByteArray()
        val totalFrameSize = frameBytes.size

        // Divoom 0x44 header:
        // [0x44, TotalL, TotalH, FrameID_L, FrameID_H, Delay_L, Delay_H, ResetPalette(0), PaletteSize_L, PaletteSize_H]
        val header = byteArrayOf(
            0x44,
            (totalFrameSize and 0xFF).toByte(),
            ((totalFrameSize shr 8) and 0xFF).toByte(),
            0x00, 0x00, // Frame 0
            0x00, 0x00, // Delay
            0x00,       // Reset palette
            (paletteSize and 0xFF).toByte(),
            ((paletteSize shr 8) and 0xFF).toByte()
        )

        val fullPayload = header + frameBytes
        val packets = mutableListOf<ByteArray>()

        // Split into chunks under 400 bytes to respect Bluetooth RFCOMM buffer limits
        var pos = 0
        while (pos < fullPayload.size) {
            val chunkSize = minOf(400, fullPayload.size - pos)
            val chunk = fullPayload.copyOfRange(pos, pos + chunkSize)
            packets.add(wrapPacket(chunk))
            pos += chunkSize
        }

        return packets
    }

    private fun wrapPacket(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        val checksum = sum and 0xFFFF

        val raw = ByteArrayOutputStream().apply {
            write(0x01)
            write(length and 0xFF)
            write((length shr 8) and 0xFF)
            write(payload)
            write(checksum and 0xFF)
            write((checksum shr 8) and 0xFF)
            write(0x02)
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
