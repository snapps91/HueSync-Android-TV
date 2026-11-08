package com.snapps91.huesync

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * MediaProjection -> SurfaceTexture (external OES) -> N x 1 pbuffer.
 * Each output pixel is a macro-color for one entertainment channel. 96 weighted taps
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
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val matrix = FloatArray(16)
    private val pixels = ByteBuffer.allocateDirect(channels.size * 4).order(ByteOrder.nativeOrder())
    private val raw = FloatArray(channels.size * 3)
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val decode = FloatArray(256) {
        val s = it / 255f
        if (s <= .04045f) s / 12.92f else ((s + .055f) / 1.055f).pow(2.4f)
    }
    private val cadence = FrameCadence(options.fps)
    private var sampleQueued = false
    internal var acquiredFrames = 0L
        private set
    internal var analyzedFrames = 0L
        private set
    private val drawLatest = Runnable {
        sampleQueued = false
        if (!closed) try { sample() } catch (e: Exception) { onError(e) }
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
            initializeEgl()
            initializeTexture()
            initializeProgram()
            return createInputSurface(width, height)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, null, 0, null, 0)) {
            "EGL is unavailable"
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, channels.size, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "Could not initialize the GPU" }
        renderer = glGetString(GL_RENDERER) ?: "OpenGL ES"
        check(glGetString(GL_EXTENSIONS)?.contains("GL_OES_EGL_image_external") == true) {
            "GPU does not support OES textures"
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
        check(linked[0] != 0) { "Shader GPU: ${glGetProgramInfoLog(program)}" }
        glUseProgram(program)
        glUniform1i(glGetUniformLocation(program, "uTexture"), 0)
        transformLocation = glGetUniformLocation(program, "uTransform")
        positionLocation = glGetAttribLocation(program, "aPosition")
        // This context belongs exclusively to this sampler; invariant GL state is set once.
        glViewport(0, 0, channels.size, 1)
        glActiveTexture(GL_TEXTURE0)
        glEnableVertexAttribArray(positionLocation)
        glVertexAttribPointer(positionLocation, 2, GL_FLOAT, false, 0, vertices)
    }

    private fun createInputSurface(width: Int, height: Int): Surface {
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ scheduleSample() }, handler)
        }
        return Surface(surfaceTexture).also { inputSurface = it }
    }

    private fun scheduleSample() {
        if (closed || sampleQueued) return
        // Coalesce notifications before updateTexImage, then acquire the newest frame.
        sampleQueued = true
        val wait = cadence.delayNanos(SystemClock.elapsedRealtimeNanos())
        if (wait == 0L) drawLatest.run()
        else handler.postDelayed(drawLatest, (wait + 999_999L) / 1_000_000L)
    }
    private fun shader(): String {
        // Compile channel positions into constant branches; avoids ES2 dynamic uniform-array indexing limits.
        val centers = channels.mapIndexed { index, c ->
            "${if (index == 0) "if" else "else if"}(gl_FragCoord.x < ${index + 1}.0) center=vec2(${c.sampleX(options.invertHorizontal)},${c.screenY});"
        }.joinToString("\n")
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTransform;
            vec3 linearize(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
            void main(){
                vec2 center=vec2(0.5);
                $centers
                vec3 sum=vec3(0.0); float total=0.0;
                for(int y=0;y<8;y++) for(int x=0;x<12;x++){
                    vec2 offset=vec2((float(x)+0.5)/12.0-0.5,(float(y)+0.5)/8.0-0.5);
                    vec2 screen=clamp(center+offset*vec2(0.65,0.65),vec2(0.0),vec2(1.0));
                    screen.y=${options.crop} + screen.y*${1f - 2f * options.crop};
                    // SurfaceTexture transform maps producer coordinates, including its Y inversion.
                    vec2 uv=(uTransform*vec4(screen.x,1.0-screen.y,0.0,1.0)).xy;
                    vec3 c=linearize(texture2D(uTexture,uv).rgb);
                    float weight=exp(-dot(offset,offset)*6.0);
                    sum+=c*weight; total+=weight;
                }
                vec3 mean=sum/total;
                vec3 srgb=mix(mean*12.92,1.055*pow(mean,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),mean));
                gl_FragColor=vec4(srgb,1.0);
            }
        """.trimIndent()
    }
    private fun sample() {
        val st = surfaceTexture ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        st.updateTexImage()
        val acquiredAt = SystemClock.elapsedRealtimeNanos()
        acquisitionMs = (acquiredAt - now) / 1_000_000f
        acquiredFrames++
        cadence.sampled(now)
        st.getTransformMatrix(matrix)
        glUniformMatrix4fv(transformLocation, 1, false, matrix, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        pixels.position(0)
        glReadPixels(0, 0, channels.size, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        drawReadbackMs = (SystemClock.elapsedRealtimeNanos() - acquiredAt) / 1_000_000f
        val error = glGetError()
        check(error == GL_NO_ERROR) { "GPU readback failed ($error)" }
        for (i in channels.indices) for (component in 0..2)
            raw[i * 3 + component] = decode[pixels.get(i * 4 + component).toInt() and 255]
        analyzedFrames++
        onColor(raw, (SystemClock.elapsedRealtimeNanos() - now) / 1_000_000f, renderer)
    }
    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source); glCompileShader(shader)
        val status = IntArray(1); glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = glGetShaderInfoLog(shader); glDeleteShader(shader)
            error("Shader compilation failed: $log")
        }
        return shader
    }
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(drawLatest)
        surfaceTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release(); surfaceTexture?.release()
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                if (program != 0) glDeleteProgram(program)
                if (texture != 0) glDeleteTextures(1, intArrayOf(texture), 0)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }
}
