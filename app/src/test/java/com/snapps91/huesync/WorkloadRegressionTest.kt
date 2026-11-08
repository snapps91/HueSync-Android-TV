package com.snapps91.huesync

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

class WorkloadRegressionTest {
    @Test fun coalescingKeepsThirtyDeadlinesPerSecondWithoutDrift() {
        val cadence = FrameCadence(30)
        val start = 1_000_000_000L
        repeat(300) { n ->
            val deadline = start + n * (1_000_000_000L / 30)
            assertEquals(0L, cadence.delayNanos(deadline))
            cadence.sampled(deadline)
            assertEquals(16_666_667L, cadence.delayNanos(deadline + 16_666_666L))
        }
    }
    @Test fun delayedFramesNeverCauseCatchupBursts() {
        val cadence = FrameCadence(50)
        cadence.sampled(1_000_000_000)
        cadence.sampled(1_500_000_000)
        assertEquals(20_000_000L, cadence.delayNanos(1_500_000_000))
        assertEquals(0L, cadence.delayNanos(1_520_000_000))
    }
    @Test fun cachedColorsAreExactlyEquivalentToOriginalMathAcrossChanges() {
        val random = java.util.Random(17)
        val reference = FloatArray(12)
        val filter = ColorFilter(4)
        var previous = 0L
        var raw = FloatArray(12)
        repeat(600) { frame ->
            if (frame % 7 == 0) raw = FloatArray(12) { random.nextInt(256) / 255f }
            val options = SyncOptions(brightness = if (frame < 300) .75f else .5f,
                saturation = 1.15f, smoothingMs = 100)
            val now = 1_000_000_000L + frame * 33_333_333L
            val seconds = if (previous == 0L) 1.0 else (now - previous) / 1e9
            val alpha = (1 - exp(-seconds * 1000 / options.smoothingMs)).toFloat()
            previous = now
            for (base in raw.indices step 3) {
                val luma = .2126f * raw[base] + .7152f * raw[base + 1] + .0722f * raw[base + 2]
                for (component in 0..2) {
                    val linear = ((luma + (raw[base + component] - luma) * options.saturation)
                        .coerceIn(0f,1f) * options.brightness)
                    val target = if (linear <= .0031308f) linear * 12.92f else 1.055f * linear.pow(1f/2.4f) - .055f
                    reference[base + component] += (target - reference[base + component]) * alpha
                }
            }
            assertArrayEquals(reference, filter.apply(raw, now, options), 0f)
        }
    }
    @Test fun stableSnapshotsAreReusedButNeverMutatedAfterPublishing() {
        val filter = ColorFilter(1)
        val options = SyncOptions(smoothingMs=0)
        val red = filter.apply(floatArrayOf(1f,0f,0f), 1_000_000_000L, options)
        val saved = red.copyOf()
        assertSame(red, filter.apply(floatArrayOf(1f,0f,0f), 2_000_000_000L, options))
        assertNotSame(red, filter.apply(floatArrayOf(0f,1f,0f), 3_000_000_000L, options))
        assertArrayEquals(saved, red, 0f)
    }
    @Test fun keepaliveChangesOnlyTheSequenceByte() {
        val packet = HuePacket("00112233-4455-6677-8899-aabbccddeeff", listOf(HueChannel(1,0f,0f)))
        val original = packet.update(floatArrayOf(.2f,.8f,.3f)).copyOf()
        packet.advanceSequence()
        assertEquals(1, packet.bytes[11].toInt())
        packet.bytes[11] = original[11]
        assertArrayEquals(original, packet.bytes)
    }
}
