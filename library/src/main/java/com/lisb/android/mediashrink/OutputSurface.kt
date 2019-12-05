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

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.view.Surface
import timber.log.Timber

/**
 * Holds state associated with a Surface used for MediaCodec decoder output.
 *
 *
 * The (width,height) constructor for this class will prepare GL, create a SurfaceTexture,
 * and then create a Surface for that SurfaceTexture.  The Surface can be passed to
 * MediaCodec.configure() to receive decoder output.  When a frame arrives, we latch the
 * texture with updateTexImage, then render the texture with GL to a pbuffer.
 *
 *
 * The no-arg constructor skips the GL preparation step and doesn't allocate a pbuffer.
 * Instead, it just creates the Surface and SurfaceTexture, and when a frame arrives
 * we just draw it on whatever surface is current.
 *
 *
 * By default, the Surface will be using a BufferQueue in asynchronous mode, so we
 * can potentially drop frames.
 */
internal class OutputSurface(rotation: Float) : OnFrameAvailableListener {
    private var mSurfaceTexture: SurfaceTexture? = null
    /**
     * Returns the Surface that we draw onto.
     */
    var surface: Surface? = null
        private set
    private val mFrameSyncObject = Object() // guards mFrameAvailable
    private var mFrameAvailable = false
    private var mTextureRender: TextureRender? = null

    /**
     * Creates an OutputSurface using the current EGL context.  Creates a Surface that can be
     * passed to MediaCodec.configure().
     */
    init {
        setup(rotation)
    }
    /**
     * Creates instances of TextureRender and SurfaceTexture, and a Surface associated
     * with the SurfaceTexture.
     */
    @SuppressLint("Recycle")
    private fun setup(rotation: Float) {
        mTextureRender = TextureRender(rotation)
        mTextureRender!!.surfaceCreated()
        // Even if we don't access the SurfaceTexture after the constructor returns, we
        // still need to keep a reference to it.  The Surface doesn't retain a reference
        // at the Java level, so if we don't either then the object can get GCed, which
        // causes the native finalizer to run.
        Timber.tag(TAG).v("textureID=%d", mTextureRender!!.textureId)
        mSurfaceTexture = SurfaceTexture(mTextureRender!!.textureId)
        // This doesn't work if OutputSurface is created on the thread that CTS started for
        // these test cases.
        //
        // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
        // create a Handler that uses it.  The "frame available" message is delivered
        // there, but since we're not a Looper-based thread we'll never see it.  For
        // this to do anything useful, OutputSurface must be created on a thread without
        // a Looper, so that SurfaceTexture uses the main application Looper instead.
        //
        // Java language note: passing "this" out of a constructor is generally unwise,
        // but we should be able to get away with it here.
        mSurfaceTexture!!.setOnFrameAvailableListener(this)
        surface = Surface(mSurfaceTexture)
    }

    /**
     * Discard all resources held by this class, notably the EGL context.
     */
    fun release() {
        surface!!.release()
        // this causes a bunch of warnings that appear harmless but might confuse someone:
        //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        //mSurfaceTexture.release();
        // null everything out so future attempts to use this object will cause an NPE
        mTextureRender = null
        surface = null
        mSurfaceTexture = null
    }

    /**
     * Replaces the fragment shader.
     */
    fun changeFragmentShader(fragmentShader: String) {
        mTextureRender!!.changeFragmentShader(fragmentShader)
    }

    /**
     * Draw the next buffer into the texture.  Must be called from the thread that created
     * the OutputSurface object, after the onFrameAvailable callback has signaled that new
     * data is available.
     */
    fun drawNewImage(snapshotOptions: SnapshotOptions?) {
        synchronized(mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    mFrameSyncObject.wait()
                } catch (ie: InterruptedException) { // shouldn't happen
                    throw RuntimeException(ie)
                }
            }
            mFrameAvailable = false
        }
        // Latch the data.
        mTextureRender!!.checkGlError("before updateTexImage")
        mSurfaceTexture!!.updateTexImage()
        mTextureRender!!.drawFrame(mSurfaceTexture!!, snapshotOptions)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        Timber.tag(TAG).v("new frame available")
        synchronized(mFrameSyncObject) {
            if (mFrameAvailable) {
                throw RuntimeException("mFrameAvailable already set, frame could be dropped")
            }
            mFrameAvailable = true
            mFrameSyncObject.notifyAll()
        }
    }

    companion object {
        private const val TAG = "OutputSurface"
    }

}