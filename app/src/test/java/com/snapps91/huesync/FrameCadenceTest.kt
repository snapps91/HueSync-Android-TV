package com.snapps91.huesync

import org.junit.Assert.*
import org.junit.Test

class FrameCadenceTest {
    @Test fun adaptiveRateIsCappedAndRequiresRepeatedPressure() {
        val cadence = FrameCadence(50, adaptive = true)
        assertEquals(20, cadence.currentFps)
        var now = 1_000_000_000L
        repeat(2) {
            cadence.sampled(now)
            cadence.completed(12_000_000, now + 12_000_000)
            now += 50_000_000
        }
        assertEquals(20, cadence.currentFps)
        cadence.sampled(now)
        cadence.completed(12_000_000, now + 12_000_000)
        assertEquals(15, cadence.currentFps)
        repeat(12) {
            now += 100_000_000
            cadence.sampled(now)
            cadence.completed(20_000_000, now + 20_000_000)
        }
        assertEquals(10, cadence.currentFps)
    }

    @Test fun recoveryNeedsSustainedHeadroomAndStaysBelowTheSelectedRate() {
        val cadence = FrameCadence(15, adaptive = true)
        var now = 1_000_000_000L
        repeat(3) {
            cadence.sampled(now)
            cadence.completed(20_000_000, now + 20_000_000)
            now += 100_000_000
        }
        assertEquals(10, cadence.currentFps)
        repeat(20) {
            cadence.sampled(now)
            cadence.completed(1_000_000, now + 1_000_000)
            now += 100_000_000
        }
        assertEquals(10, cadence.currentFps)
        cadence.sampled(now)
        cadence.completed(1_000_000, now + 1_000_000)
        assertEquals(15, cadence.currentFps)
        repeat(50) {
            now += 100_000_000
            cadence.sampled(now)
            cadence.completed(1_000_000, now + 1_000_000)
        }
        assertEquals(15, cadence.currentFps)
    }

    @Test fun isolatedSpikeDoesNotLowerTheRateAndLateCompletionLeavesBreathingRoom() {
        val cadence = FrameCadence(30, adaptive = true)
        var now = 1_000_000_000L
        repeat(10) { index ->
            cadence.sampled(now)
            val work = if (index % 2 == 0) 20_000_000L else 1_000_000L
            cadence.completed(work, now + work)
            now += 50_000_000
        }
        assertEquals(20, cadence.currentFps)
        repeat(3) {
            cadence.sampled(now)
            cadence.completed(200_000_000, now + 200_000_000)
            now += 250_000_000
        }
        assertEquals(15, cadence.currentFps)
        assertTrue(cadence.delayNanos(now - 50_000_000) > 0)
    }

    @Test fun fixedCadenceIgnoresWorkBudgetAndMinimumSelectionIsHonored() {
        val fixed = FrameCadence(50)
        val minimum = FrameCadence(10, adaptive = true)
        repeat(100) {
            fixed.completed(100_000_000, it * 1_000_000_000L)
            minimum.completed(0, it * 1_000_000_000L)
        }
        assertEquals(50, fixed.currentFps)
        assertEquals(10, minimum.currentFps)
    }
}
