package com.snapps91.huesync

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.pow

/**
 * MediaProjection -> SurfaceTexture (external OES) -> N x 1 pbuffer.
 * Each output pixel is a macro-color for one entertainment channel. 24 or 96 weighted taps
 * per channel are reduced in a fragment shader in linear light. CPU readback: N*4 bytes.
 * All methods, frame callbacks and cleanup run on the same dedicated EGL thread.
 */
class GpuColorSampler(
    private val channels: List<HueChannel>, private val options: SyncOptions,
    private val handler: Handler, private val onColor: (FloatArray, Float, String) -> Unit,
    private val onError: (Exception) -> Unit
) : Closeable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var vertexBuffer = 0
    private var readback: GpuReadback? = null
    private var readbackPending = false
    private var pendingFrame = false
    private var sampleStarted = 0L
    private var acquiredAt = 0L
    private var pollDelayMs = 2L
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val matrix = FloatArray(16)
    private val pixels = ByteBuffer.allocateDirect(channels.size * 4).order(ByteOrder.nativeOrder())
    private val packed = ByteArray(channels.size * 4)
    private val lastPacked = ByteArray(packed.size)
    private val raw = FloatArray(channels.size * 3)
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val decode = FloatArray(256) {
        val s = it / 255f
        if (s <= .04045f) s / 12.92f else ((s + .055f) / 1.055f).pow(2.4f)
    }
    private val cadence = FrameCadence(options.fps, options.prioritizeVideo)
    private var lastTimestamp = Long.MIN_VALUE
    private var samplingVideo = false
    private val onFrame = SurfaceTexture.OnFrameAvailableListener {
        if (!closed) {
            pendingFrame = true
            scheduleSample()
        }
    }
    private var sampleQueued = false
    internal var acquiredFrames = 0L
        private set
    internal var analyzedFrames = 0L
        private set
    private val drawLatest = Runnable {
        sampleQueued = false
        if (!closed) try { sample() } catch (e: Exception) { onError(e) }
    }
    private val pollReadback = object : Runnable {
        override fun run() {
            if (closed || !readbackPending) return
            try {
                if (checkNotNull(readback).readIfReady(pixels)) {
                    readbackPending = false
                    publishColors()
                    if (samplingVideo || pendingFrame) scheduleSample()
                } else {
                    check(SystemClock.elapsedRealtimeNanos() - sampleStarted < 500_000_000L) {
                        English.GPU_READBACK_TIMEOUT
                    }
                    // Back off rather than spin or repeatedly wake a busy TV CPU.
                    handler.postDelayed(this, pollDelayMs)
                    pollDelayMs = (pollDelayMs * 2).coerceAtMost(16)
                }
            } catch (e: Exception) { onError(e) }
        }
    }
    private var closed = false
    private var renderer = ""
    @Volatile var acquisitionMs = 0f
        private set
    @Volatile var drawReadbackMs = 0f
        private set
    private var transformLocation = -1
    private var positionLocation = -1

    fun open(width: Int, height: Int): Surface {
        require(channels.isNotEmpty() && channels.size <= HuePacket.MAX_CHANNELS)
        try {
            if (options.prioritizeVideo) {
                try { initializeGpu(3) } catch (_: Exception) {
                    // Try ES2 on the same consumer before resorting to the RGBA capture path.
                    releaseGl()
                    initializeGpu(2)
                }
            } else initializeGpu(2)
            return createInputSurface(width, height)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun initializeGpu(version: Int) {
        initializeEgl(version)
        initializeTexture()
        initializeProgram()
        if (version == 3) {
            val transfer = GpuReadback(channels.size)
            readback = transfer // Keep ownership even if buffer allocation fails.
            transfer.open()
        }
        renderer = English.gpuRenderer(renderer, readback != null)
    }

    private fun initializeEgl(version: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, null, 0, null, 0)) {
            English.EGL_IS_UNAVAILABLE
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE,
            if (version == 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = checkNotNull(configs[0])
        val attributesForContext = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE)
        // EGL_IMG_context_priority lets the driver schedule playback ahead of our sampling.
        if (options.prioritizeVideo && EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
                ?.split(' ')?.contains("EGL_IMG_context_priority") == true) {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG, EGL14.EGL_NONE), 0)
        }
        if (context == EGL14.EGL_NO_CONTEXT) {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attributesForContext, 0)
        }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, channels.size, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { English.COULD_NOT_INITIALIZE_THE_GPU }
        renderer = glGetString(GL_RENDERER) ?: "OpenGL ES"
        check(glGetString(GL_EXTENSIONS)?.contains("GL_OES_EGL_image_external") == true) {
            English.GPU_DOES_NOT_SUPPORT_OES_TEXTURES
        }
    }

    private fun initializeTexture() {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        texture = ids[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun initializeProgram() {
        program = glCreateProgram()
        val vertex = compile(GL_VERTEX_SHADER, "attribute vec2 aPosition; void main(){gl_Position=vec4(aPosition,0.0,1.0);}")
        val fragment = compile(GL_FRAGMENT_SHADER, shader())
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        val linked = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { English.shaderGpu(glGetProgramInfoLog(program)) }
        glUseProgram(program)
        glUniform1i(glGetUniformLocation(program, "uTexture"), 0)
        transformLocation = glGetUniformLocation(program, "uTransform")
        positionLocation = glGetAttribLocation(program, "aPosition")
        // This context belongs exclusively to this sampler; invariant GL state is set once.
        glViewport(0, 0, channels.size, 1)
        glActiveTexture(GL_TEXTURE0)
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        vertexBuffer = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferData(GL_ARRAY_BUFFER, 8 * 4, vertices, GL_STATIC_DRAW)
        glEnableVertexAttribArray(positionLocation)
        glVertexAttribPointer(positionLocation, 2, GL_FLOAT, false, 0, 0)
        check(glGetError() == GL_NO_ERROR) { English.COULD_NOT_INITIALIZE_THE_GPU }
    }

    private fun createInputSurface(width: Int, height: Int): Surface {
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener(onFrame, handler)
        }
        return Surface(surfaceTexture).also { inputSurface = it }
    }

    private fun scheduleSample() {
        if (closed || sampleQueued || readbackPending) return
        // Coalesce notifications before updateTexImage, then acquire the newest frame.
        sampleQueued = true
        val wait = cadence.delayNanos(SystemClock.elapsedRealtimeNanos())
        if (wait == 0L) handler.post(drawLatest)
        else handler.postDelayed(drawLatest, (wait + 999_999L) / 1_000_000L)
    }
    private fun shader(): String {
        val columns = options.sampleColumns
        val rows = options.sampleRows
        // Compile channel positions into constant branches; avoids ES2 dynamic uniform-array indexing limits.
        val centers = channels.mapIndexed { index, c ->
            "${if (index == 0) "if" else "else if"}(gl_FragCoord.x < ${index + 1}.0) center=vec2(${c.sampleX(options.invertHorizontal)},${c.screenY});"
        }.joinToString("\n")
        val kernel = if (options.prioritizeVideo) {
            val weights = DoubleArray(columns * rows) { i ->
                val x = (i % columns + .5) / columns - .5
                val y = (i / columns + .5) / rows - .5
                exp(-6.0 * (x * x + y * y))
            }
            val total = weights.sum()
            buildString {
                appendLine("vec3 mean=vec3(0.0);")
                for (i in weights.indices) {
                    val dx = (((i % columns + .5) / columns - .5) * .65).toFloat()
                    val dy = (((i / columns + .5) / rows - .5) * .65).toFloat()
                    appendLine("mean+=sampleAt(center+vec2($dx,$dy))*${(weights[i] / total).toFloat()};")
                }
            }
        } else """
                vec3 sum=vec3(0.0); float total=0.0;
                for(int y=0;y<$rows;y++) for(int x=0;x<$columns;x++){
                    vec2 offset=vec2((float(x)+0.5)/$columns.0-0.5,(float(y)+0.5)/$rows.0-0.5);
                    vec3 c=sampleAt(center+offset*0.65);
                    float weight=exp(-dot(offset,offset)*6.0);
                    sum+=c*weight; total+=weight;
                }
                vec3 mean=sum/total;
        """.trimIndent()
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTransform;
            vec3 linearize(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
            vec3 sampleAt(vec2 point){
                vec2 screen=clamp(point,vec2(0.0),vec2(1.0));
                screen.y=${options.crop} + screen.y*${1f - 2f * options.crop};
                vec2 uv=(uTransform*vec4(screen.x,1.0-screen.y,0.0,1.0)).xy;
                return linearize(texture2D(uTexture,uv).rgb);
            }
            void main(){
                vec2 center=vec2(0.5);
                $centers
                $kernel
                vec3 srgb=mix(mean*12.92,1.055*pow(mean,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),mean));
                gl_FragColor=vec4(srgb,1.0);
            }
        """.trimIndent()
    }
    private fun sample() {
        val st = surfaceTexture ?: return
        val notified = pendingFrame
        pendingFrame = false
        val now = SystemClock.elapsedRealtimeNanos()
        sampleStarted = now
        st.updateTexImage()
        acquiredFrames++
        acquiredAt = SystemClock.elapsedRealtimeNanos()
        acquisitionMs = (acquiredAt - now) / 1_000_000f
        cadence.sampled(now)
        if (options.prioritizeVideo) {
            val timestamp = st.timestamp
            if (!notified && timestamp == lastTimestamp) {
                if (samplingVideo) {
                    // Return to event-driven idle. Probe once after arming the listener
                    // to cover a final frame queued between acquisition and registration.
                    samplingVideo = false
                    st.setOnFrameAvailableListener(onFrame, handler)
                    scheduleSample()
                }
                return
            }
            lastTimestamp = timestamp
            // Producers with no usable timestamp must remain notification-driven.
            if (!samplingVideo && timestamp > 0) {
                samplingVideo = true
                st.setOnFrameAvailableListener(null)
            }
        }
        st.getTransformMatrix(matrix)
        glUniformMatrix4fv(transformLocation, 1, false, matrix, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        val transfer = readback
        if (transfer != null) {
            transfer.submit()
            readbackPending = true
            pollDelayMs = 2L
            pollReadback.run()
        } else {
            pixels.position(0)
            glReadPixels(0, 0, channels.size, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            publishColors()
            if (samplingVideo) scheduleSample()
        }
    }

    private fun publishColors() {
        val completed = SystemClock.elapsedRealtimeNanos()
        // Wall time includes fence polling delay on ES3; this is not a GPU execution timer.
        drawReadbackMs = (completed - acquiredAt) / 1_000_000f
        cadence.completed(completed - sampleStarted, completed)
        val error = glGetError()
        check(error == GL_NO_ERROR) { English.gpuReadback(error) }
        pixels.position(0)
        pixels.get(packed)
        if (!packed.contentEquals(lastPacked)) {
            for (i in channels.indices) for (component in 0..2)
                raw[i * 3 + component] = decode[packed[i * 4 + component].toInt() and 255]
            packed.copyInto(lastPacked)
        }
        analyzedFrames++
        onColor(raw, (completed - sampleStarted) / 1_000_000f, renderer)
    }
    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source); glCompileShader(shader)
        val status = IntArray(1); glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = glGetShaderInfoLog(shader); glDeleteShader(shader)
            error(English.shaderCompilation(log))
        }
        return shader
    }
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(drawLatest)
        handler.removeCallbacks(pollReadback)
        readbackPending = false
        surfaceTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release(); surfaceTexture?.release()
        releaseGl()
    }

    private fun releaseGl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                readback?.close()
                if (vertexBuffer != 0) glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
                if (program != 0) glDeleteProgram(program)
                if (texture != 0) glDeleteTextures(1, intArrayOf(texture), 0)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        readback = null
        vertexBuffer = 0
        program = 0
        texture = 0
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }
    private companion object {
        const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
        const val EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103
    }
}
