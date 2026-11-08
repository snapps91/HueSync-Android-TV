package com.snapps91.huesync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** HueStream 2.0: 16-byte header, ASCII area UUID, and seven bytes per channel. */
class HuePacket(areaId: String, channels: List<HueChannel>) {
    val bytes: ByteArray
    private var sequence = 0

    init {
        require(UUID.fromString(areaId).toString() == areaId.lowercase())
        require(channels.isNotEmpty() && channels.size <= MAX_CHANNELS)
        require(channels.map { it.id }.distinct().size == channels.size)
        require(channels.all { it.id in 0..255 })

        bytes = ByteArray(HEADER_BYTES + channels.size * CHANNEL_BYTES)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            put("HueStream".toByteArray(Charsets.US_ASCII))
            put(byteArrayOf(2, 0, 0, 0, 0, 0, 0))
            put(areaId.toByteArray(Charsets.US_ASCII))
            channels.forEach { channel ->
                put(channel.id.toByte())
                repeat(3) { putShort(0) }
            }
        }
    }

    fun update(rgb: FloatArray): ByteArray {
        require(rgb.size == (bytes.size - HEADER_BYTES) / CHANNEL_BYTES * 3)
        advanceSequence()
        for (i in rgb.indices) {
            val value = (rgb[i].coerceIn(0f, 1f) * 65535f + .5f).toInt()
            val offset = HEADER_BYTES + 1 + (i / 3) * CHANNEL_BYTES + (i % 3) * 2
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }
        return bytes
    }

    fun advanceSequence() {
        bytes[11] = sequence++.toByte()
    }

    companion object {
        const val MAX_CHANNELS = 20
        private const val HEADER_BYTES = 52
        private const val CHANNEL_BYTES = 7
    }
}
