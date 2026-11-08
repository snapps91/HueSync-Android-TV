package com.snapps91.huesync

/** Screen coordinates use a top-left origin. Hue x is horizontal; z is height. */
data class HueChannel(val id: Int, val x: Float, val height: Float) {
    val screenX: Float get() = ((x + 1f) * .5f).coerceIn(.08f, .92f)
    val screenY: Float get() = ((1f - height) * .5f).coerceIn(.08f, .92f)

    fun sampleX(invertHorizontal: Boolean): Float =
        if (invertHorizontal) ((1f - x) * .5f).coerceIn(.08f, .92f) else screenX
}

data class HueArea(val id: String, val name: String, val channels: List<HueChannel>, val active: Boolean)
data class Bridge(val ip: String, val id: String, val name: String)
data class Credentials(val bridge: Bridge, val applicationKey: String, val clientKey: String)

data class SyncOptions(
    val fps: Int = 30,
    val brightness: Float = .75f,
    val smoothingMs: Int = 100,
    val saturation: Float = 1.15f,
    val crop: Float = .08f,
    val invertHorizontal: Boolean = true
) {
    init {
        require(fps in 10..50)
        require(brightness in 0f..1f)
        require(smoothingMs in 0..500)
        require(saturation in 0f..2f)
        require(crop in 0f..0.2f)
    }
}

data class SyncSnapshot(
    val running: Boolean = false,
    val message: String = "Ready to connect a Bridge",
    val fps: Int = 0,
    val gpuMs: Float = 0f,
    val channels: Int = 0,
    val renderer: String = "GPU OpenGL ES",
    val colors: IntArray = intArrayOf(),
    val frames: Long = 0,
    val acquisitionMs: Float = 0f,
    val drawReadbackMs: Float = 0f
)

object SyncStatus {
    @Volatile var value = SyncSnapshot()
}
