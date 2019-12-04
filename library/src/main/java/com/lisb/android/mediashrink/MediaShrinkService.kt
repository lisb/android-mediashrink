package com.lisb.android.mediashrink

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.*
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MediaShrinkService : Service() {
    private val lock = Object()
    private lateinit var mediaShrink: MediaShrink
    private lateinit var messenger: Messenger
    private lateinit var bgThread: HandlerThread
    private lateinit var executorService: ExecutorService

    override fun onCreate() {
        Timber.tag(TAG).v("onCreate")
        super.onCreate()
        executorService = Executors.newSingleThreadExecutor()
        bgThread = HandlerThread("request-queue-thread")
        bgThread.start()
        val handler = ReceiveRequestHandler(bgThread.looper)
        messenger = Messenger(handler)
        mediaShrink = MediaShrink(this)
        mediaShrink.onProgressListener = handler
    }

    override fun onBind(intent: Intent): IBinder? {
        Timber.tag(TAG).v("onBind")
        mediaShrink.width = intent.getIntExtra(EXTRA_WIDTH, -1)
        mediaShrink.videoBitRate = intent.getIntExtra(EXTRA_VIDEO_BITRATE, -1)
        mediaShrink.audioBitRate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, -1)
        mediaShrink.durationLimit = intent.getLongExtra(EXTRA_DURATION_LIMIT, -1)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.tag(TAG).v("onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Timber.tag(TAG).v("onDestroy")
        super.onDestroy()
        bgThread.quit()
        executorService.shutdown()
        Process.killProcess(Process.myPid())
    }

    private inner class ReceiveRequestHandler(looper: Looper) : Handler(looper), OnProgressListener, UnrecoverableErrorCallback {
        private var currentMessage: Message? = null
        override fun handleMessage(msg: Message) {
            Timber.tag(TAG).d("ReceiveRequestHandler#handleMessage. what:%d", msg.what)
            when (msg.what) {
                REQUEST_SHRINK_MSGID -> handleRequestShrink(msg)
            }
        }

        private fun handleRequestShrink(msg: Message) {
            currentMessage = msg
            executorService.execute {
                try {
                    val inputUri = msg.data.getParcelable<Uri>(REQUEST_SHRINK_INPUT_URI)!!
                    val outputPath = msg.data.getString(REQUEST_SHRINK_OUTPUT_PATH)!!
                    mediaShrink.shrink(inputUri, outputPath, this@ReceiveRequestHandler)
                    respondSafely(Message.obtain(null, RESULT_COMPLETE_MSGID))
                } catch (e: IOException) {
                    Timber.tag(TAG).e(e, "Failed to media shrink")
                    val response = Message.obtain()
                    response.what = RESULT_RECOVERABLE_ERROR_MSGID
                    val data = Bundle()
                    data.putSerializable(RESULT_RECOVERABLE_ERROR_EXCEPTION, e)
                    response.data = data
                    respondSafely(response)
                } catch (e: TooMovieLongException) {
                    Timber.tag(TAG).e(e, "Failed to media shrink")
                    val response = Message.obtain()
                    response.what = RESULT_RECOVERABLE_ERROR_MSGID
                    val data = Bundle()
                    data.putSerializable(RESULT_RECOVERABLE_ERROR_EXCEPTION, e)
                    response.data = data
                    respondSafely(response)
                }
                currentMessage = null
                synchronized(lock) { lock.notifyAll() }
            }
            while (currentMessage != null) {
                try {
                    synchronized(lock) { lock.wait() }
                } catch (e: InterruptedException) {
                    Timber.tag(TAG).e(e, "interrupted")
                }
            }
        }

        private fun respondSafely(message: Message) {
            try {
                Timber.tag(TAG).d("respondSafely. type:%d", message.what)
                currentMessage!!.replyTo.send(message)
            } catch (e: RemoteException) {
                Timber.tag(TAG).e(e, "Failed to respond")
            }
        }

        override fun onProgress(progress: Int) {
            respondSafely(Message.obtain(null, RESULT_PROGRESS_MSGID, progress, 0))
        }

        override fun onUnrecoverableError(e: Throwable) {
            Timber.tag(TAG).e(e, "Unrecoverable error occurred.")
            val response = Message.obtain()
            response.what = RESULT_UNRECOVERABLE_ERROR_MSGID
            val data = Bundle()
            data.putSerializable(RESULT_UNRECOVERABLE_ERROR_EXCEPTION, e)
            response.data = data
            respondSafely(response)
            Process.killProcess(Process.myPid())
        }
    }

    companion object {
        const val EXTRA_WIDTH = "width"
        const val EXTRA_VIDEO_BITRATE = "video-bitrate"
        const val EXTRA_AUDIO_BITRATE = "audio-bitrate"
        const val EXTRA_DURATION_LIMIT = "duration-limit"
        const val REQUEST_SHRINK_MSGID = 1
        const val REQUEST_SHRINK_INPUT_URI = "input"
        const val REQUEST_SHRINK_OUTPUT_PATH = "output"
        /** arg1 は進捗率  */
        const val RESULT_PROGRESS_MSGID = 1
        const val RESULT_COMPLETE_MSGID = 2
        const val RESULT_RECOVERABLE_ERROR_MSGID = 3
        const val RESULT_RECOVERABLE_ERROR_EXCEPTION = "exception"
        const val RESULT_UNRECOVERABLE_ERROR_MSGID = 4
        const val RESULT_UNRECOVERABLE_ERROR_EXCEPTION = "exception"
        private val TAG = MediaShrinkService::class.java
                .simpleName
    }
}