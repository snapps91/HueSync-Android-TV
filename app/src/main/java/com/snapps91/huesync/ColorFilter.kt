package com.snapps91.huesync

import kotlin.math.exp
import kotlin.math.pow

/** Time-based smoothing keeps the chosen response stable when frames are dropped. */
class ColorFilter(channels: Int) {
    private val smoothed = FloatArray(channels * 3)
    private val cachedRaw = FloatArray(channels * 3)
    private val target = FloatArray(channels * 3)
    private var previousNanos = 0L
    private var cachedOptions: SyncOptions? = null
    private var published = FloatArray(channels * 3)

    fun apply(raw: FloatArray, now: Long, options: SyncOptions): FloatArray {
        require(raw.size == smoothed.size)
        val elapsedSeconds = if (previousNanos == 0L) 1.0 else (now - previousNanos).coerceAtLeast(0) / 1e9
        val alpha = if (options.smoothingMs == 0) 1f else
            (1 - exp(-elapsedSeconds * 1000 / options.smoothingMs)).toFloat()
        previousNanos = now

        if (cachedOptions != options || !raw.contentEquals(cachedRaw)) {
            raw.copyInto(cachedRaw)
            cachedOptions = options
            updateTarget(raw, options)
        }
        for (i in smoothed.indices) smoothed[i] += (target[i] - smoothed[i]) * alpha

        // The DTLS thread may still read the previous result; publish a new array only when it changes.
        if (!smoothed.contentEquals(published)) published = smoothed.copyOf()
        return published
    }

    private fun updateTarget(raw: FloatArray, options: SyncOptions) {
        for (channel in 0 until raw.size / 3) {
            val base = channel * 3
            val luma = .2126f * raw[base] + .7152f * raw[base + 1] + .0722f * raw[base + 2]
            for (component in 0..2) {
                // Sampling returns linear light; Hue expects sRGB-encoded values.
                val linear = ((luma + (raw[base + component] - luma) * options.saturation)
                    .coerceIn(0f, 1f) * options.brightness)
                target[base + component] = if (linear <= .0031308f) linear * 12.92f else
                    1.055f * linear.pow(1f / 2.4f) - .055f
            }
        }
    }
}
