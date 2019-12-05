/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lisb.android.mediashrink

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Code for rendering a texture onto a surface using OpenGL ES 2.0.
 */
internal class TextureRender(private val mRotation: Float) {
    private val mTriangleVerticesData = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f)
    private val mTriangleVertices: FloatBuffer
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private var mProgram = 0
    var textureId = -12345
        private set
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    fun drawFrame(st: SurfaceTexture, snapshotOptions: SnapshotOptions?) {
        checkGlError("onDrawFrame start")
        st.getTransformMatrix(mSTMatrix)
        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")
        android.opengl.Matrix.setIdentityM(mMVPMatrix, 0)
        android.opengl.Matrix.rotateM(mMVPMatrix, 0, mRotation, 0f, 0f, 1.0f)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        if (snapshotOptions != null) {
            val pixelBuf = ByteBuffer.allocateDirect(snapshotOptions.width
                    * snapshotOptions.height * 4)
            pixelBuf.order(ByteOrder.LITTLE_ENDIAN)
            GLES20.glReadPixels(0, 0, snapshotOptions.width, snapshotOptions.height, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, pixelBuf)
            val snapshotBase = Bitmap.createBitmap(snapshotOptions.width, snapshotOptions.height,
                    Bitmap.Config.ARGB_8888)
            snapshotBase.copyPixelsFromBuffer(pixelBuf)
            // OpenGL と bitmap は y 座標の方向が真逆なので反転する
            val m = Matrix()
            m.postScale(1f, -1f)
            val snapshot = Bitmap.createBitmap(snapshotBase, 0, 0, snapshotOptions.width,
                    snapshotOptions.height, m, true)
            snapshotBase.recycle()
            var out: FileOutputStream? = null
            try {
                out = FileOutputStream(snapshotOptions.file)
                snapshot.compress(CompressFormat.JPEG, 80, out)
            } catch (e: FileNotFoundException) {
                Timber.tag(TAG).e(e, "Failed to create snapshot file. file:%s", snapshotOptions.file)
            } finally {
                Utils.closeSilently(out)
            }
            snapshot.recycle()
        }
        GLES20.glFinish()
    }

    /**
     * Initializes GL state.  Call this after the EGL surface has been created and made current.
     */
    fun surfaceCreated() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        checkGlError("glBindTexture mTextureID")
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")
    }

    /**
     * Replaces the fragment shader.
     */
    fun changeFragmentShader(fragmentShader: String) {
        GLES20.glDeleteProgram(mProgram)
        mProgram = createProgram(VERTEX_SHADER, fragmentShader)
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Timber.tag(TAG).e("Could not compile shader %d:%s", shaderType, GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            Timber.tag(TAG).e("Could not create program")
            return program
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Timber.tag(TAG).e("Could not link program: %s", GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    fun checkGlError(op: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Timber.tag(TAG).e("%s: glError:%d", op, error)
            throw RuntimeException("$op: glError $error")
        }
    }

    companion object {
        private const val TAG = "TextureRender"
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
        private const val VERTEX_SHADER = """uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""
        private const val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;
void main() {
  gl_FragColor = texture2D(sTexture, vTextureCoord);
}
"""
    }

    init {
        mTriangleVertices = ByteBuffer.allocateDirect(
                mTriangleVerticesData.size * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        mTriangleVertices.put(mTriangleVerticesData).position(0)
        android.opengl.Matrix.setIdentityM(mSTMatrix, 0)
    }
}