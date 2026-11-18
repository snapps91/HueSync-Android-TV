package com.snapps91.huesync

import kotlin.math.exp
import kotlin.math.pow

/** Time-based smoothing keeps the chosen response stable when frames are dropped. */
class ColorFilter(channels: Int) {
    internal val streamColors = FloatArray(channels * 3)
    private var publicInput: FloatArray? = null
    private var currentInput: FloatArray? = null
    private var settled = false
    private val target = FloatArray(channels * 3)
    private var previousNanos = 0L
    private var cachedOptions: SyncOptions? = null
    private var published = FloatArray(channels * 3)

    fun apply(raw: FloatArray, now: Long, options: SyncOptions): FloatArray {
        // The public API accepts reusable input arrays and returns immutable snapshots.
        val input = publicInput?.takeIf { it.contentEquals(raw) } ?: raw.copyOf().also { publicInput = it }
        advanceSnapshot(input, now, options)
        if (!published.contentEquals(streamColors)) published = streamColors.copyOf()
        return published
    }

    /** Network-thread path: capture publishes immutable inputs; output never leaves this thread. */
    internal fun advanceSnapshot(raw: FloatArray, now: Long, options: SyncOptions): Boolean {
        require(raw.size == streamColors.size)
        val previous = previousNanos
        previousNanos = now
        if (raw !== currentInput || options != cachedOptions) {
            updateTarget(raw, options)
            currentInput = raw
            cachedOptions = options
        } else if (settled) return false

        val elapsed = if (previous == 0L) 1.0 else (now - previous).coerceAtLeast(0) / 1e9
        val alpha = if (options.smoothingMs == 0) 1f else
            (1 - exp(-elapsed * 1000 / options.smoothingMs)).toFloat()
        var changed = false
        settled = true
        for (i in streamColors.indices) {
            val old = streamColors[i]
            val value = old + (target[i] - old) * alpha
            streamColors[i] = value
            if (value != old) changed = true
            if (value != target[i]) settled = false
        }
        return changed
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
