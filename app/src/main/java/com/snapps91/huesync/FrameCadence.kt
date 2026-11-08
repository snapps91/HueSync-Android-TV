package com.snapps91.huesync

/** Absolute deadlines avoid drift and never turn delayed work into a catch-up burst. */
internal class FrameCadence(fps: Int) {
    private val period = 1_000_000_000L / fps.also { require(it in 10..50) }
    private var next = 0L

    fun delayNanos(now: Long): Long = (next - now).coerceAtLeast(0)

    fun sampled(now: Long) {
        next = if (next == 0L || now - next >= period) now + period else next + period
    }
}
