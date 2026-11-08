package com.snapps91.huesync

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class RgbaMacroSamplerTest {
    private fun plane(width: Int, height: Int, padding: Int, pixelStride: Int,
        pixel: (Int,Int) -> Int): Pair<ByteBuffer,Int> {
        val stride = width * pixelStride + padding
        val result = ByteBuffer.allocate(stride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val value = pixel(x,y); val offset = y * stride + x * pixelStride
            result.put(offset,(value ushr 16).toByte())
            result.put(offset+1,(value ushr 8).toByte())
            result.put(offset+2,value.toByte())
            result.put(offset+3,255.toByte())
        }
        return result to stride
    }
    @Test fun paddedRowsAndWidePixelStridesDoNotPolluteColors() {
        val (buffer,stride) = plane(37,23,17,8) { _,_ -> 0x00ff00 }
        val rgb = RgbaMacroSampler(37,23,listOf(HueChannel(1,0f,0f)),.08f).sample(buffer,stride,8)
        assertArrayEquals(floatArrayOf(0f,1f,0f),rgb,.0001f)
    }
    @Test fun quadrantsRemainInTheCorrectScreenOrder() {
        val colors = intArrayOf(0xff0000,0x00ff00,0x0000ff,0xffffff)
        val (buffer,stride) = plane(128,72,0,4) { x,y -> colors[(if(y<36) 0 else 2)+(if(x<64) 0 else 1)] }
        val channels = listOf(HueChannel(0,-1f,1f),HueChannel(1,1f,1f),HueChannel(2,-1f,-1f),HueChannel(3,1f,-1f))
        val rgb = RgbaMacroSampler(128,72,channels,.08f).sample(buffer,stride,4)
        assertArrayEquals(floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 1f,1f,1f),rgb,.0001f)
    }
    @Test fun horizontalCalibrationExchangesSampledSides() {
        val (buffer,stride) = plane(128,72,0,4) { x,_ -> if (x < 64) 0xff0000 else 0x0000ff }
        val channels = listOf(HueChannel(0,-1f,0f),HueChannel(1,1f,0f))
        assertArrayEquals(floatArrayOf(0f,0f,1f, 1f,0f,0f),
            RgbaMacroSampler(128,72,channels,0f,true).sample(buffer,stride,4),.0001f)
    }
    @Test fun blackRemainsBlackWithoutInventingProtectedVideoColors() {
        val (buffer,stride) = plane(32,18,0,4) { _,_ -> 0 }
        assertArrayEquals(FloatArray(3),RgbaMacroSampler(32,18,listOf(HueChannel(0,0f,0f)),0f)
            .sample(buffer,stride,4),0f)
    }
    @Test fun cachedAddressesFollowStrideChangesWithoutChangingColors() {
        val channels = listOf(HueChannel(0,-.7f,.4f), HueChannel(1,.6f,-.3f))
        val sampler = RgbaMacroSampler(37,23,channels,.08f)
        fun pattern(x: Int, y: Int) = ((x * 7 % 256) shl 16) or
            ((y * 11 % 256) shl 8) or ((x * 3 + y * 5) % 256)
        val (reference, referenceStride) = plane(37,23,0,4,::pattern)
        val expected = sampler.sample(reference,referenceStride,4).copyOf()
        for ((padding,pixelStride) in listOf(17 to 8, 9 to 4, 17 to 8, 0 to 4)) {
            val (buffer,stride) = plane(37,23,padding,pixelStride,::pattern)
            repeat(2) { assertArrayEquals(expected,sampler.sample(buffer,stride,pixelStride),0f) }
        }
    }
    @Test fun rejectsTruncatedPlanesInsteadOfReadingOutsideTheBuffer() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaMacroSampler(32,18,listOf(HueChannel(0,0f,0f)),0f).sample(ByteBuffer.allocate(12),128,4)
        }
    }
}
