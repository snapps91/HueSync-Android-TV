package com.snapps91.huesync

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.Closeable

/** Owns the single VirtualDisplay and switches its consumer when GPU capture fails. */
internal class CaptureCoordinator(
    private val context: Context,
    private val projection: MediaProjection,
    private val handler: Handler,
    private val area: HueArea,
    private val options: SyncOptions,
    private val isActive: () -> Boolean,
    private val onSample: (FloatArray, Float, String) -> Unit,
    private val onFailure: (String) -> Unit
) : Closeable {
    private val filter = ColorFilter(area.channels.size)
    private var virtualDisplay: VirtualDisplay? = null
    private var gpuSampler: GpuColorSampler? = null
    private var rgbaSampler: CompatibilityColorSampler? = null
    private var usingRgba = false
    private var receivedFrame = false
    private var closed = false

    val acquisitionMs: Float get() = gpuSampler?.acquisitionMs ?: 0f
    val drawReadbackMs: Float get() = gpuSampler?.drawReadbackMs ?: 0f

    @Suppress("DEPRECATION")
    fun open() {
        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val height = (CAPTURE_WIDTH.toFloat() * metrics.heightPixels / metrics.widthPixels)
            .toInt().coerceAtLeast(1)

        val initialSurface = try {
            GpuColorSampler(area.channels, options, handler, ::receiveFrame, { switchToRgba(height) })
                .also { gpuSampler = it }.open(CAPTURE_WIDTH, height)
        } catch (_: Exception) {
            switchToRgba(height)
            null
        }
        if (!isActive() || closed) return

        // The projection token is single-use, including when GPU setup fails.
        val surface = initialSurface ?: rgbaSampler?.surface ?: error(English.CAPTURE_SURFACE_UNAVAILABLE)
        virtualDisplay = projection.createVirtualDisplay(
            "HueSync", CAPTURE_WIDTH, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
        )
        if (!usingRgba) watchFirstFrame { switchToRgba(height) }
    }

    private fun receiveFrame(raw: FloatArray, sampleMs: Float, renderer: String) {
        receivedFrame = true
        onSample(filter.apply(raw, SystemClock.elapsedRealtimeNanos(), options), sampleMs, renderer)
    }

    private fun switchToRgba(height: Int) {
        if (closed || !isActive() || usingRgba) return
        usingRgba = true
        try {
            virtualDisplay?.surface = null
            gpuSampler?.close()
            gpuSampler = null
            receivedFrame = false
            val surface = CompatibilityColorSampler(
                area.channels, options, handler, ::receiveFrame,
                { onFailure(English.captureUnavailable(UserMessage.from(it))) }
            ).also { rgbaSampler = it }.open(CAPTURE_WIDTH, height)
            virtualDisplay?.surface = surface
            watchFirstFrame { onFailure(English.NO_FRAME_RECEIVED_FROM_ANDROID) }
        } catch (e: Exception) {
            onFailure(English.captureUnavailable(UserMessage.from(e)))
        }
    }

    private fun watchFirstFrame(onTimeout: () -> Unit) {
        handler.postDelayed({ if (!closed && isActive() && !receivedFrame) onTimeout() }, FIRST_FRAME_TIMEOUT_MS)
    }

    override fun close() {
        if (closed) return
        closed = true
        virtualDisplay?.release()
        virtualDisplay = null
        gpuSampler?.close()
        gpuSampler = null
        rgbaSampler?.close()
        rgbaSampler = null
    }

    companion object {
        private const val CAPTURE_WIDTH = 384
        private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
    }
}
