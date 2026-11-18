package com.snapps91.huesync

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class GpuReadbackTest {
    @Test fun asynchronousTransfersAreCompleteReusableAndCanBeClosedWhilePending() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        var transfer: GpuReadback? = null
        try {
            assumeTrue(EGL14.eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            assumeTrue(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
            ), 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            assumeTrue(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 20, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            val pixels = ByteBuffer.allocateDirect(80)
            val reader = GpuReadback(20).also { transfer = it; it.open() }
            repeat(6) { frame ->
                val color = FloatArray(3) { if (it == frame % 3) 1f else 0f }
                glClearColor(color[0], color[1], color[2], 1f)
                glClear(GL_COLOR_BUFFER_BIT)
                reader.submit()
                assertThrows(IllegalStateException::class.java) { reader.submit() }
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (!reader.readIfReady(pixels)) {
                    assertTrue("GPU transfer did not finish", SystemClock.uptimeMillis() < deadline)
                    SystemClock.sleep(2)
                }
                repeat(20) { channel ->
                    repeat(3) { component ->
                        assertEquals((color[component] * 255).toInt(), pixels.get(channel * 4 + component).toInt() and 255)
                    }
                    assertEquals(255, pixels.get(channel * 4 + 3).toInt() and 255)
                }
            }
            reader.submit()
            reader.close()
            reader.close()
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            transfer?.close()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }
}
