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
                val options = SyncOptions(invertHorizontal=inverted)
                sampler = CompatibilityColorSampler(channels,options,handler,
                    { raw,_,_ -> result.set(raw.copyOf()); completed.countDown() },
                    { failure.set(it); completed.countDown() })
                val surface = sampler!!.open(options.captureWidth,options.captureWidth*9/16)
                val canvas = surface.lockCanvas(null)
                val paint = Paint()
                val colors = intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE)
                if (black) canvas.drawColor(Color.BLACK)
                else for(i in 0..3) {
                    paint.color = colors[i]
                    val w = canvas.width/2f; val h = canvas.height/2f
                    val x = (i%2)*w; val y=(i/2)*h
                    canvas.drawRect(x,y,x+w,y+h,paint)
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
