package com.snapps91.huesync

import org.junit.Assert.*
import org.junit.Test

class ColorPipelineTest {
    private val area = "00112233-4455-6677-8899-aabbccddeeff"
    @Test fun packetHasExactHueV2WireLayout() {
        val encoder = HuePacket(area, listOf(HueChannel(7, 0f, 0f), HueChannel(255, 0f, 0f)))
        val bytes = encoder.update(floatArrayOf(1f, 0f, .5f, 0f, 1f, 0f))
        assertEquals(66, bytes.size)
        assertEquals("HueStream", String(bytes, 0, 9, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(2,0,0,0,0,0,0), bytes.copyOfRange(9,16))
        assertEquals(area, String(bytes, 16, 36, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(7, -1,-1, 0,0, -128,0), bytes.copyOfRange(52,59))
        assertArrayEquals(byteArrayOf(-1, 0,0, -1,-1, 0,0), bytes.copyOfRange(59,66))
    }
    @Test fun sequenceWrapsAndColorsClamp() {
        val encoder = HuePacket(area, listOf(HueChannel(0, 0f, 0f)))
        repeat(256) { assertEquals(it, encoder.update(floatArrayOf(2f, -1f, 0f))[11].toInt() and 255) }
        val frame = encoder.update(floatArrayOf(2f, -1f, 0f))
        assertEquals(0, frame[11].toInt())
        assertEquals(255, frame[53].toInt() and 255)
        assertEquals(0, frame[55].toInt())
    }
    @Test fun rejectsAmbiguousChannels() {
        assertThrows(IllegalArgumentException::class.java) { HuePacket(area, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            HuePacket(area, listOf(HueChannel(1,0f,0f), HueChannel(1,1f,0f)))
        }
    }
    @Test fun smoothingIsIndependentOfFrameRate() {
        val options = SyncOptions(brightness=1f, saturation=1f, smoothingMs=100)
        fun afterStep(step: Long): Float {
            val filter = ColorFilter(1)
            filter.apply(floatArrayOf(0f,0f,0f), 1_000_000_000L, options)
            var output = floatArrayOf()
            for (t in step..100_000_000L step step) output = filter.apply(floatArrayOf(1f,1f,1f), 1_000_000_000L+t, options)
            return output[0]
        }
        assertEquals(afterStep(10_000_000), afterStep(20_000_000), .0001f)
        assertEquals(.63212f, afterStep(10_000_000), .0001f)
    }
    @Test fun brightnessUsesLinearLightAndBlackRemainsBlack() {
        val options = SyncOptions(brightness=.25f, saturation=1f, smoothingMs=0)
        val result = ColorFilter(1).apply(floatArrayOf(1f,0f,0f), 1L, options)
        assertEquals(.5371f, result[0], .001f)
        assertEquals(0f, result[1], 0f)
        assertArrayEquals(floatArrayOf(0f,0f,0f), ColorFilter(1).apply(FloatArray(3), 1L, options), 0f)
    }
    @Test fun screenMappingKeepsVerticalAndHorizontalOrientation() {
        assertEquals(.08f, HueChannel(1,-1f,1f).screenX, 0f)
        assertEquals(.08f, HueChannel(1,-1f,1f).screenY, 0f)
        assertEquals(.92f, HueChannel(2,1f,-1f).screenY, 0f)
    }
    @Test fun horizontalCalibrationPreservesVerticalPositionAndChannelId() {
        val left = HueChannel(7,-1f,1f)
        val right = HueChannel(9,1f,-1f)
        assertEquals(.08f,left.sampleX(false),0f)
        assertEquals(.92f,left.sampleX(true),0f)
        assertEquals(.08f,right.sampleX(true),0f)
        assertEquals(.5f,HueChannel(5,0f,0f).sampleX(true),0f)
        assertEquals(.08f,left.screenY,0f)
        assertEquals(.92f,right.screenY,0f)
        assertEquals(7,left.id)
    }

}
