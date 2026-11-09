package com.snapps91.huesync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/** Same 12×8 weighted spatial kernel, with RGBA row/pixel strides and GL-style bilinear sampling. */
internal class RgbaMacroSampler(
    width: Int,
    height: Int,
    channels: List<HueChannel>,
    crop: Float,
    invertHorizontal: Boolean = false
) {
    private val raw = FloatArray(channels.size * 3)
    private val offsets = IntArray(channels.size * TAPS_PER_CHANNEL * 4)
    private val byteOffsets = IntArray(offsets.size)
    private var cachedRowStride = -1
    private var cachedPixelStride = -1
    private val weights = FloatArray(offsets.size)
    private val decode = FloatArray(256) {
        val s = it / 255f
        if (s <= .04045f) s / 12.92f else ((s + .055f) / 1.055f).pow(2.4f)
    }
    private val imageWidth = width
    private val imageHeight = height
    private val tapWeights = FloatArray(channels.size * TAPS_PER_CHANNEL)

    init {
        require(width > 0 && height > 0 && channels.isNotEmpty() && channels.size <= HuePacket.MAX_CHANNELS)
        require(crop in 0f..0.2f)
        var weightSum = 0f
        for (y in 0..7) for (x in 0..11) {
            val ox = (x + .5f) / 12f - .5f
            val oy = (y + .5f) / 8f - .5f
            weightSum += exp(-(ox * ox + oy * oy) * 6f)
        }
        for ((c, channel) in channels.withIndex()) for (y in 0..7) for (x in 0..11) {
            val ox = (x + .5f) / 12f - .5f
            val oy = (y + .5f) / 8f - .5f
            val sx = (channel.sampleX(invertHorizontal) + ox * .65f).coerceIn(0f, 1f)
            val sy = crop + (channel.screenY + oy * .65f).coerceIn(0f, 1f) * (1f - crop * 2f)
            val fx = sx * width - .5f
            val fy = sy * height - .5f
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val dx = fx - x0
            val dy = fy - y0
            val base = (c * TAPS_PER_CHANNEL + y * 12 + x) * 4
            offsets[base] = y0.coerceIn(0, height - 1) * width + x0.coerceIn(0, width - 1)
            offsets[base + 1] = y0.coerceIn(0, height - 1) * width + (x0 + 1).coerceIn(0, width - 1)
            offsets[base + 2] = (y0 + 1).coerceIn(0, height - 1) * width + x0.coerceIn(0, width - 1)
            offsets[base + 3] = (y0 + 1).coerceIn(0, height - 1) * width + (x0 + 1).coerceIn(0, width - 1)
            weights[base] = (1f - dx) * (1f - dy)
            weights[base + 1] = dx * (1f - dy)
            weights[base + 2] = (1f - dx) * dy
            weights[base + 3] = dx * dy
            tapWeights[c * TAPS_PER_CHANNEL + y * 12 + x] = exp(-(ox * ox + oy * oy) * 6f) / weightSum
        }
    }

    fun sample(buffer: ByteBuffer, rowStride: Int, pixelStride: Int): FloatArray {
        require(pixelStride >= 4 && rowStride >= imageWidth * pixelStride)
        require(buffer.limit() >= (imageHeight - 1) * rowStride + (imageWidth - 1) * pixelStride + 4)
        updateByteOffsets(rowStride, pixelStride)
        // Absolute packed reads preserve the caller's position and byte order.
        val littleEndian = buffer.order() == ByteOrder.LITTLE_ENDIAN
        val redShift = if (littleEndian) 0 else 24
        val greenShift = if (littleEndian) 8 else 16
        val blueShift = if (littleEndian) 16 else 8
        raw.fill(0f)
        for (channel in 0 until raw.size / 3) for (tap in 0 until TAPS_PER_CHANNEL) {
            val index = channel * TAPS_PER_CHANNEL + tap
            val base = index * 4
            var red = 0f
            var green = 0f
            var blue = 0f
            for (corner in 0..3) {
                val pixel = buffer.getInt(byteOffsets[base + corner])
                val weight = weights[base + corner]
                red += ((pixel ushr redShift) and 255) * weight
                green += ((pixel ushr greenShift) and 255) * weight
                blue += ((pixel ushr blueShift) and 255) * weight
            }
            val weight = tapWeights[index]
            raw[channel * 3] += linearize(red) * weight
            raw[channel * 3 + 1] += linearize(green) * weight
            raw[channel * 3 + 2] += linearize(blue) * weight
        }
        return raw
    }

    private fun linearize(srgb: Float): Float {
        // Preserve bilinear interpolation before linearizing, as in the OES shader.
        val low = srgb.toInt().coerceIn(0, 255)
        val high = (low + 1).coerceAtMost(255)
        return decode[low] + (decode[high] - decode[low]) * (srgb - low)
    }

    private fun updateByteOffsets(rowStride: Int, pixelStride: Int) {
        if (rowStride == cachedRowStride && pixelStride == cachedPixelStride) return
        for (i in offsets.indices) {
            val pixel = offsets[i]
            byteOffsets[i] = (pixel / imageWidth) * rowStride + (pixel % imageWidth) * pixelStride
        }
        cachedRowStride = rowStride
        cachedPixelStride = pixelStride
    }

    companion object {
        private const val TAPS_PER_CHANNEL = 96
    }
}
