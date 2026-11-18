package com.snapps91.huesync

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable

/**
 * Alternative native BufferQueue consumer for firmware/apps with an OES capture problem.
 * Still honors MediaProjection/secure-content restrictions. No privileged capture or DRM bypass.
 * Reads only weighted sample locations from a mapped RGBA plane, never creates a Bitmap.
 */
class CompatibilityColorSampler(
    private val channels: List<HueChannel>,
    private val options: SyncOptions,
    private val handler: Handler,
    private val onColor: (FloatArray, Float, String) -> Unit,
    private val onError: (Exception) -> Unit
) : Closeable {
    private var reader: ImageReader? = null
    private var kernel: RgbaMacroSampler? = null
    private val cadence = FrameCadence(options.fps, options.prioritizeVideo)
    private var queued = false
    private var closed = false
    private var samplingVideo = false
    private val onImage = ImageReader.OnImageAvailableListener { scheduleSample() }

    internal val surface: Surface? get() = reader?.surface

    private val sampleLatest = Runnable {
        queued = false
        if (closed) return@Runnable
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            val current = reader ?: return@Runnable
            val latest = current.acquireLatestImage()
            cadence.sampled(started)
            if (latest == null) {
                if (samplingVideo) {
                    samplingVideo = false
                    current.setOnImageAvailableListener(onImage, handler)
                    scheduleSample() // Cover a frame arriving before the listener was rearmed.
                }
                return@Runnable
            }
            latest.use { image ->
                if (options.prioritizeVideo && !samplingVideo) {
                    samplingVideo = true
                    current.setOnImageAvailableListener(null, null)
                }
                val plane = image.planes.first()
                val sampler = checkNotNull(kernel)
                val colors = sampler.sample(plane.buffer, plane.rowStride, plane.pixelStride)
                val completed = SystemClock.elapsedRealtimeNanos()
                cadence.completed(completed - started, completed)
                onColor(colors, (completed - started) / 1_000_000f,
                    English.IMAGEREADER_RGBA_COMPATIBILITY)
            }
            if (samplingVideo) scheduleSample()
        } catch (e: Exception) {
            onError(e)
        }
    }

    fun open(width: Int, height: Int): Surface {
        check(reader == null && !closed)
        kernel = RgbaMacroSampler(width, height, channels, options.crop, options.invertHorizontal,
            options.sampleColumns, options.sampleRows)
        val current = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = current
        current.setOnImageAvailableListener(onImage, handler)
        return current.surface
    }

    private fun scheduleSample() {
        if (closed || queued) return
        queued = true
        val wait = cadence.delayNanos(SystemClock.elapsedRealtimeNanos())
        if (wait == 0L) handler.post(sampleLatest)
        else handler.postDelayed(sampleLatest, (wait + 999_999L) / 1_000_000L)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(sampleLatest)
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        kernel = null
    }
}
