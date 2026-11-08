package com.snapps91.huesync

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CompatibilitySamplerTest {
    private fun sample(black: Boolean, inverted: Boolean = false): FloatArray {
        val worker = HandlerThread("ImageReader-capture-test").apply { start() }
        val handler = Handler(worker.looper)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val result = AtomicReference<FloatArray>()
        var sampler: CompatibilityColorSampler? = null
        handler.post {
            try {
                val channels = listOf(HueChannel(0,-1f,1f),HueChannel(1,1f,1f),
                    HueChannel(2,-1f,-1f),HueChannel(3,1f,-1f))
                sampler = CompatibilityColorSampler(channels,SyncOptions(invertHorizontal=inverted),handler,
                    { raw,_,_ -> result.set(raw.copyOf()); completed.countDown() },
                    { failure.set(it); completed.countDown() })
                val surface = sampler!!.open(384,216)
                val canvas = surface.lockCanvas(null)
                val paint = Paint()
                val colors = intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE)
                if (black) canvas.drawColor(Color.BLACK)
                else for(i in 0..3) {
                    paint.color = colors[i]
                    val x = (i%2)*192f; val y=(i/2)*108f
                    canvas.drawRect(x,y,x+192,y+108,paint)
                }
                surface.unlockCanvasAndPost(canvas)
            } catch(e: Throwable) { failure.set(e); completed.countDown() }
        }
        try {
            assertTrue("No ImageReader frame received",completed.await(10,TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Native RGBA capture failed",it) }
            return result.get() ?: error("No RGBA colors")
        } finally {
            val closed = CountDownLatch(1)
            handler.post { sampler?.close(); worker.quitSafely(); closed.countDown() }
            closed.await(4,TimeUnit.SECONDS)
        }
    }
    @Test fun nativeRgbaSurfaceProducesCorrectSpatialColors() {
        assertArrayEquals(floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 1f,1f,1f),sample(false),.03f)
    }
    @Test fun nativeRgbaCalibrationSwapsOnlyHorizontalPositions() {
        assertArrayEquals(floatArrayOf(0f,1f,0f, 1f,0f,0f, 1f,1f,1f, 0f,0f,1f),sample(false,true),.03f)
    }
    @Test fun blackNativeFramesAreReportedAsBlack() {
        assertArrayEquals(FloatArray(12),sample(true),0f)
    }
    @Test fun protectedCapturePermissionsAreNotAvailableToTheApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for(permission in listOf("android.permission.CAPTURE_VIDEO_OUTPUT",
            "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT","android.permission.READ_FRAME_BUFFER")) {
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            Log.i("HueSyncCapture", "$permission granted=$granted")
            assertFalse("This app must not silently acquire privileged capture permissions",granted)
        }
    }
}
