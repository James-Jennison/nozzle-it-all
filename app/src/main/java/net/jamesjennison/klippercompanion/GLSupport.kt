package net.jamesjennison.klippercompanion

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared by every GLES30 renderer in this app (ModelViewer.kt's mesh view, SlicedPreview.kt's
 * toolpath view) - shader compilation and vertex buffer packing are identical regardless of what
 * gets drawn, so this is the one place that logic lives.
 */

internal fun compileShader(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    val status = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
    check(status[0] != 0) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
    return shader
}

internal fun buildGLProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES30.glCreateProgram()
    GLES30.glAttachShader(program, vertex)
    GLES30.glAttachShader(program, fragment)
    GLES30.glLinkProgram(program)
    val status = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
    check(status[0] != 0) { "Shader link failed: ${GLES30.glGetProgramInfoLog(program)}" }
    GLES30.glDeleteShader(vertex)
    GLES30.glDeleteShader(fragment)
    return program
}

internal fun directFloatBuffer(data: FloatArray) =
    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

private const val UPLOAD_CHUNK_FLOATS = 262_144 // 1 MB: big enough to be quick, small enough never to be the allocation that fails

/** Uploads [data] to the currently bound GL_ARRAY_BUFFER in small pieces. A single direct buffer for a big mesh is a heap allocation of the same size on ART. */
internal fun uploadArrayBuffer(data: FloatArray) {
    android.opengl.GLES30.glBufferData(android.opengl.GLES30.GL_ARRAY_BUFFER, data.size * 4, null, android.opengl.GLES30.GL_STATIC_DRAW)
    if (data.isEmpty()) return
    val chunk = ByteBuffer.allocateDirect(minOf(data.size, UPLOAD_CHUNK_FLOATS) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    var offset = 0
    while (offset < data.size) {
        val n = minOf(UPLOAD_CHUNK_FLOATS, data.size - offset)
        chunk.clear(); chunk.put(data, offset, n); chunk.position(0)
        android.opengl.GLES30.glBufferSubData(android.opengl.GLES30.GL_ARRAY_BUFFER, offset * 4, n * 4, chunk)
        offset += n
    }
}
