package com.snapps91.huesync

import android.graphics.Canvas
import android.graphics.Bitmap
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
import java.nio.ByteBuffer

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
                val surface = sampler!!.open(options.captureWidth, options.captureWidth * 9 / 16)
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
                val w = canvas.width / 2f; val h = canvas.height / 2f
                val x = (i % 2) * w; val y = (i / 2) * h
                canvas.drawRect(x,y,x+w,y+h,paint)
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
                val w = canvas.width / 2f; val h = canvas.height / 2f
                val x=(i%2)*w; val y=(i/2)*h
                canvas.drawRect(x,y,x+w,y+h,paint)
            }
        }
        assertArrayEquals(floatArrayOf(0f,1f,0f, 1f,0f,0f, 1f,1f,1f, 0f,0f,1f),rgb,.03f)
    }

    @Test fun bothGpuProfilesMatchTheRgbaKernelAcrossTwentyChannels() {
        val channels = List(20) { HueChannel(it, (it % 5) / 2f - 1f, (it / 5) / 1.5f - 1f) }
        for (priority in listOf(true, false)) {
            val options = SyncOptions(crop=.2f, invertHorizontal=true, prioritizeVideo=priority)
            val width = options.captureWidth
            val height = width * 9 / 16
            val rgba = ByteBuffer.allocate(width * height * 4)
            val argb = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val r = x * 255 / (width - 1)
                val g = y * 255 / (height - 1)
                rgba.put(r.toByte()).put(g.toByte()).put(128.toByte()).put(255.toByte())
                Color.rgb(r, g, 128)
            }
            val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
            try {
                val expected = RgbaMacroSampler(width,height,channels,options.crop,options.invertHorizontal,
                    options.sampleColumns,options.sampleRows).sample(rgba,width*4,4)
                val actual = sample(channels, options) { it.drawBitmap(bitmap,0f,0f,null) }
                assertArrayEquals("GPU/RGBA mismatch for prioritizeVideo=$priority", expected, actual, .015f)
            } finally { bitmap.recycle() }
        }
    }

}
