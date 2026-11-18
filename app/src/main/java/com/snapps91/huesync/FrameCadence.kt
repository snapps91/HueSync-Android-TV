package com.snapps91.huesync

/** Absolute deadlines avoid drift and never turn delayed work into a catch-up burst. */
internal class FrameCadence(fps: Int, private val adaptive: Boolean = false) {
    private val ceiling = fps.also { require(it in 10..50) }.let { if (adaptive) minOf(it, 20) else it }
    var currentFps = ceiling
        private set
    private val period: Long get() = 1_000_000_000L / currentFps
    private var next = 0L
    private var lastSample = 0L
    private var expensiveSamples = 0
    private var inexpensiveSince = 0L

    fun delayNanos(now: Long): Long = (next - now).coerceAtLeast(0)

    fun sampled(now: Long) {
        lastSample = now
        next = if (next == 0L || now - next >= period) now + period else next + period
    }

    /** Conservative wall-time budget, including acquisition and asynchronous completion waits.
     * This measures our pipeline, not player FPS or total system GPU utilization.
     */
    fun completed(workNanos: Long, now: Long) {
        if (!adaptive) return
        if (workNanos > period / 10) {
            inexpensiveSince = 0L
            if (++expensiveSamples >= 3) changeRate((currentFps - 5).coerceAtLeast(10), now)
        } else {
            expensiveSamples = 0
            val faster = (currentFps + 5).coerceAtMost(ceiling)
            // Require headroom at the faster rate for two seconds before recovering.
            if (workNanos < (1_000_000_000L / faster) / 20) {
                if (inexpensiveSince == 0L) inexpensiveSince = now
                if (now - inexpensiveSince >= 2_000_000_000L) changeRate(faster, now)
            } else inexpensiveSince = 0L
        }
    }

    private fun changeRate(fps: Int, now: Long) {
        expensiveSamples = 0
        inexpensiveSince = 0L
        if (fps == currentFps) return
        currentFps = fps
        // A late completion must leave breathing room, never schedule a catch-up burst.
        next = maxOf(lastSample + period, now + period / 2)
    }
}
