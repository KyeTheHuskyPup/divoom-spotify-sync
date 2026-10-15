package com.backpack.spotifydivoom

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max

object DivoomPacketEncoder {

    // Switch backpack channel to custom buffer
    fun switchChannelCommand(): ByteArray {
        return wrapPacket(byteArrayOf(0x45, 0x05))
    }

    fun encodeImageToFrames(original: Bitmap): List<ByteArray> {
        val scaled = Bitmap.createScaledBitmap(original, 16, 16, false)
        val palette = mutableListOf<Int>()
        val pixelIndices = IntArray(256)

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val pixel = scaled.getPixel(x, y) and 0x00FFFFFF
                var idx = palette.indexOf(pixel)
                if (idx == -1) {
                    if (palette.size < 256) {
                        palette.add(pixel)
                        idx = palette.size - 1
                    } else {
                        idx = 0
                    }
                }
                pixelIndices[y * 16 + x] = idx
            }
        }

        val paletteCount = palette.size
        val bitsPerPixel = if (paletteCount <= 1) 1 else max(1, ceil(log2(paletteCount.toDouble())).toInt())

        val frameDataStream = ByteArrayOutputStream()

        // Write palette RGB data (3 bytes per color)
        for (col in palette) {
            frameDataStream.write((col shr 16) and 0xFF)
            frameDataStream.write((col shr 8) and 0xFF)
            frameDataStream.write(col and 0xFF)
        }

        // Bit-pack the pixel indices
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

        val rawFrameBytes = frameDataStream.toByteArray()

        // Frame header:
        // [0xAA, FrameTotalLen_L, FrameTotalLen_H, Delay_L, Delay_H, ResetPalette(0), PaletteSize_L, PaletteSize_H]
        val frameHeader = ByteArrayOutputStream().apply {
            write(0xAA)
            val frameDataSize = rawFrameBytes.size
            write(frameDataSize and 0xFF)
            write((frameDataSize shr 8) and 0xFF)
            write(0x00) // Delay L
            write(0x00) // Delay H
            write(0x00) // Palette reset flag
            write(paletteCount and 0xFF)
            write((paletteCount shr 8) and 0xFF)
        }.toByteArray()

        val fullFramePayload = frameHeader + rawFrameBytes
        val totalSize = fullFramePayload.size

        // Total stream payload prefixed with total size (32-bit little endian)
        val fullStream = ByteArrayOutputStream().apply {
            write(totalSize and 0xFF)
            write((totalSize shr 8) and 0xFF)
            write((totalSize shr 16) and 0xFF)
            write((totalSize shr 24) and 0xFF)
            write(fullFramePayload)
        }.toByteArray()

        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var packageId = 0
        val maxChunk = 350 // Safe SPP transmission chunk size

        while (offset < fullStream.size) {
            val chunkLength = minOf(maxChunk, fullStream.size - offset)
            val chunkBytes = fullStream.copyOfRange(offset, offset + chunkLength)

            // Divoom 0x44 chunk payload: [0x44, PackageId_L, PackageId_H] + ChunkBytes
            val chunkPayload = ByteArrayOutputStream().apply {
                write(0x44)
                write(packageId and 0xFF)
                write((packageId shr 8) and 0xFF)
                write(chunkBytes)
            }.toByteArray()

            packets.add(wrapPacket(chunkPayload))
            offset += chunkLength
            packageId++
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
