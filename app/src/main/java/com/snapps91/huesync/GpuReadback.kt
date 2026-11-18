package com.snapps91.huesync

import android.opengl.GLES30.*
import java.io.Closeable
import java.nio.ByteBuffer

/** One ES3 transfer in flight, never a queue of captured frames. All calls use the EGL thread. */
internal class GpuReadback(private val channels: Int) : Closeable {
    private var buffer = 0
    private var fence = 0L

    fun open() {
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        buffer = ids[0]
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer)
        glBufferData(GL_PIXEL_PACK_BUFFER, channels * 4, null, GL_STREAM_READ)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        check(buffer != 0 && glGetError() == GL_NO_ERROR) { English.GPU_READBACK_UNAVAILABLE }
    }

    fun submit() {
        check(fence == 0L) { English.GPU_READBACK_UNAVAILABLE }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer)
        glReadPixels(0, 0, channels, 1, GL_RGBA, GL_UNSIGNED_BYTE, 0)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        glFlush() // A pbuffer has no swap to submit the command stream for us.
        check(fence != 0L && glGetError() == GL_NO_ERROR) { English.GPU_READBACK_UNAVAILABLE }
    }

    /** Zero timeout: map only after completion, never wait on the CPU for GPU work. */
    fun readIfReady(destination: ByteBuffer): Boolean {
        when (glClientWaitSync(fence, 0, 0)) {
            GL_TIMEOUT_EXPIRED -> return false
            GL_ALREADY_SIGNALED, GL_CONDITION_SATISFIED -> Unit
            else -> error(English.GPU_READBACK_UNAVAILABLE)
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer)
        try {
            val mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, channels * 4, GL_MAP_READ_BIT)
                as? ByteBuffer ?: error(English.GPU_READBACK_UNAVAILABLE)
            try {
                destination.clear()
                mapped.position(0)
                mapped.limit(channels * 4)
                destination.put(mapped).rewind()
            } finally {
                check(glUnmapBuffer(GL_PIXEL_PACK_BUFFER)) { English.GPU_READBACK_UNAVAILABLE }
            }
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        }
        glDeleteSync(fence)
        fence = 0L
        check(glGetError() == GL_NO_ERROR) { English.GPU_READBACK_UNAVAILABLE }
        return true
    }

    override fun close() {
        if (fence != 0L) glDeleteSync(fence)
        if (buffer != 0) glDeleteBuffers(1, intArrayOf(buffer), 0)
        fence = 0L
        buffer = 0
    }
}
