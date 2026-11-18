package com.snapps91.huesync

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
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
    private var published = FloatArray(0)
    private var virtualDisplay: VirtualDisplay? = null
    private var gpuSampler: GpuColorSampler? = null
    private var rgbaSampler: CompatibilityColorSampler? = null
    private var usingRgba = false
    private var receivedFrame = false
    private var closed = false
    private var captureHeight = 0
    @Volatile var canSwitchToRgba = false
        private set

    val acquisitionMs: Float get() = gpuSampler?.acquisitionMs ?: 0f
    val drawReadbackMs: Float get() = gpuSampler?.drawReadbackMs ?: 0f

    @Suppress("DEPRECATION")
    fun open() {
        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val height = (options.captureWidth.toFloat() * metrics.heightPixels / metrics.widthPixels)
            .toInt().coerceAtLeast(1)
        captureHeight = height

        val initialSurface = try {
            GpuColorSampler(area.channels, options, handler, ::receiveFrame, { switchToRgba(height) })
                .also { gpuSampler = it }.open(options.captureWidth, height)
        } catch (_: Exception) {
            switchToRgba(height)
            null
        }
        if (!isActive() || closed) return

        // The projection token is single-use, including when GPU setup fails.
        val surface = initialSurface ?: rgbaSampler?.surface ?: error(English.CAPTURE_SURFACE_UNAVAILABLE)
        virtualDisplay = projection.createVirtualDisplay(
            "HueSync", options.captureWidth, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
        )
        canSwitchToRgba = virtualDisplay != null && !usingRgba
        if (!usingRgba) watchFirstFrame { switchToRgba(height) }
    }

    /** User-triggered fallback for visible artifacts that do not produce a GL error.
     * Runs on the capture thread and reuses the existing consent and VirtualDisplay.
     */
    fun useCompatibilityCapture() {
        if (canSwitchToRgba) switchToRgba(captureHeight)
    }

    private fun receiveFrame(raw: FloatArray, sampleMs: Float, renderer: String) {
        receivedFrame = true
        // Samplers reuse their scratch arrays. The network thread owns color filtering and
        // smoothing; only complete, immutable raw snapshots cross the thread boundary.
        if (!raw.contentEquals(published)) published = raw.copyOf()
        onSample(published, sampleMs, renderer)
    }

    private fun switchToRgba(height: Int) {
        if (closed || !isActive() || usingRgba) return
        canSwitchToRgba = false
        usingRgba = true
        try {
            virtualDisplay?.surface = null
            gpuSampler?.close()
            gpuSampler = null
            receivedFrame = false
            val surface = CompatibilityColorSampler(
                area.channels, options, handler, ::receiveFrame,
                { onFailure(English.captureUnavailable(UserMessage.from(it))) }
            ).also { rgbaSampler = it }.open(options.captureWidth, height)
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
        canSwitchToRgba = false
        virtualDisplay?.release()
        virtualDisplay = null
        gpuSampler?.close()
        gpuSampler = null
        rgbaSampler?.close()
        rgbaSampler = null
    }

    companion object {
        private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
    }
}
