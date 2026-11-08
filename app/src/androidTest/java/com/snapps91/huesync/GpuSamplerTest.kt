package com.snapps91.huesync

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GpuSamplerTest {
    private fun sample(channels: List<HueChannel>, options: SyncOptions = SyncOptions(invertHorizontal=false), draw: (Canvas) -> Unit): FloatArray {
        val thread = HandlerThread("GPU-test").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val result = AtomicReference<FloatArray>()
        var sampler: GpuColorSampler? = null
        handler.post {
            try {
                sampler = GpuColorSampler(channels, options, handler,
                    { rgb, _, _ -> result.set(rgb.copyOf()); latch.countDown() }, { failure.set(it); latch.countDown() })
                val surface = sampler!!.open(384,216)
                val canvas = surface.lockCanvas(null)
                draw(canvas)
                surface.unlockCanvasAndPost(canvas)
            } catch (e: Throwable) { failure.set(e); latch.countDown() }
        }
        try {
            assertTrue("No GPU frame within 10s", latch.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("GPU pipeline failed", it) }
            return result.get() ?: throw AssertionError("No colors returned")
        } finally {
            val closed = CountDownLatch(1)
            handler.post { sampler?.close(); thread.quitSafely(); closed.countDown() }
            closed.await(5, TimeUnit.SECONDS)
        }
    }
    @Test fun gpuReducesSurfaceWithoutProjectionOrFullFrameReadback() {
        val rgb = sample(listOf(HueChannel(1,0f,0f))) { it.drawColor(Color.RED) }
        assertTrue(rgb[0] > .95f)
        assertTrue(rgb[1] < .02f && rgb[2] < .02f)
    }
    @Test fun spatialSamplingPreservesTopBottomAndLeftRight() {
        val channels = listOf(HueChannel(0,-1f,1f), HueChannel(1,1f,1f),
            HueChannel(2,-1f,-1f), HueChannel(3,1f,-1f))
        val rgb = sample(channels) { canvas ->
            val paint = Paint()
            val colors = intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE)
            for (i in 0..3) {
                paint.color = colors[i]
                val x = (i % 2) * 192f; val y = (i / 2) * 108f
                canvas.drawRect(x,y,x+192,y+108,paint)
            }
        }
        assertArrayEquals(floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 1f,1f,1f), rgb, .03f)
    }
    @Test fun horizontalCalibrationSwapsSidesWithoutSwappingTopAndBottom() {
        val channels = listOf(HueChannel(0,-1f,1f), HueChannel(1,1f,1f),
            HueChannel(2,-1f,-1f), HueChannel(3,1f,-1f))
        val rgb = sample(channels, SyncOptions(invertHorizontal=true)) { canvas ->
            val paint = Paint()
            val colors = intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE)
            for (i in 0..3) {
                paint.color = colors[i]
                val x=(i%2)*192f; val y=(i/2)*108f
                canvas.drawRect(x,y,x+192,y+108,paint)
            }
        }
        assertArrayEquals(floatArrayOf(0f,1f,0f, 1f,0f,0f, 1f,1f,1f, 0f,0f,1f),rgb,.03f)
    }

}
