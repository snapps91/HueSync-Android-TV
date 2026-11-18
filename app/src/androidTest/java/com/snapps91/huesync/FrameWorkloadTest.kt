package com.snapps91.huesync

import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FrameWorkloadTest {
    @Test fun sixtyHzProducerDoesNotForceSixtyTextureAcquisitionsAtThirtyFps() {
        exerciseProducer(prioritizeVideo = false)
    }

    @Test fun videoPriorityCapsAnalysisWhileTheProducerKeepsRunningAtSixtyHz() {
        exerciseProducer(prioritizeVideo = true)
    }

    private fun exerciseProducer(prioritizeVideo: Boolean) {
        val gpuThread = HandlerThread("Gpu-cadence-test").apply { start() }
        val producerThread = HandlerThread("Surface-producer-test").apply { start() }
        val gpu = Handler(gpuThread.looper)
        val producer = Handler(producerThread.looper)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        var sampler: GpuColorSampler? = null
        var generated = 0
        var acquired = 0L
        var analyzed = 0L
        var totalAnalysisMs = 0.0
        var renderer = ""
        gpu.post {
            try {
                sampler = GpuColorSampler(List(10) { HueChannel(it, it / 5f - 1f, 0f) },
                    SyncOptions(fps=30, prioritizeVideo=prioritizeVideo), gpu,
                    { _, ms, device -> totalAnalysisMs += ms; renderer = device },
                    { failure.set(it); done.countDown() })
                val surface = sampler!!.open(if (prioritizeVideo) 128 else 384, if (prioritizeVideo) 72 else 216)
                val start = SystemClock.uptimeMillis()
                producer.post(object : Runnable {
                    override fun run() {
                        try {
                            val canvas = surface.lockCanvas(null)
                            canvas.drawColor(if (generated % 2 == 0) Color.RED else Color.BLUE)
                            surface.unlockCanvasAndPost(canvas)
                            generated++
                            if (generated < 120) producer.postAtTime(this, start + generated * 1000L / 60)
                            else gpu.postDelayed({
                                acquired = sampler!!.acquiredFrames
                                analyzed = sampler!!.analyzedFrames
                                done.countDown()
                            }, 100)
                        } catch (e: Throwable) { failure.set(e); done.countDown() }
                    }
                })
            } catch (e: Throwable) { failure.set(e); done.countDown() }
        }
        try {
            assertTrue("Frame producer did not finish", done.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("GPU cadence failed", it) }
            Log.i("HueSyncPerf", "videoPriority=$prioritizeVideo renderer=$renderer producer=$generated acquired=$acquired analyzed=$analyzed meanAnalysisMs=${totalAnalysisMs / analyzed}")
            assertEquals(120, generated)
            // Video priority probes for a fresh timestamp before sleeping on a static image.
            if (prioritizeVideo) assertTrue(analyzed <= acquired)
            else assertEquals(analyzed, acquired)
            assertTrue("Texture work was not coalesced", acquired < generated * if (prioritizeVideo) .5 else .75)
            assertTrue("Sampler stopped responding", analyzed >= if (prioritizeVideo) 15 else 30)
        } finally {
            producer.removeCallbacksAndMessages(null)
            producerThread.quitSafely()
            producerThread.join(4000)
            val closed = CountDownLatch(1)
            gpu.post { sampler?.close(); gpuThread.quitSafely(); closed.countDown() }
            closed.await(4, TimeUnit.SECONDS)
        }
    }
}
