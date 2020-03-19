package com.lisb.android.mediashrink

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.*
import timber.log.Timber
import java.io.*
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MediaShrinkQueue(private val context: Context,
                       private val handler: Handler,
                       private val workspace: File,
                       private val width: Int,
                       private val videoBitrate: Int,
                       private val audioBitrate: Int,
                       private val durationLimit: Long) {

    private val queue: Queue<Request> = ArrayDeque()
    private var connection: ServiceConnection
    private var unbindTask: Runnable

    private var sendMessenger: Messenger? = null
    private var receiveMessenger: Messenger? = null
    private var bound = false
    private var unbindInvoked = false
    private var binder: IBinder? = null

    init {
        require(!workspace.isFile) { "workspace must be directory." }
        connection = MediaShrinkServiceConnection()
        unbindTask = UnbindTask()
        cleanWorkspace()
    }

    /**
     * Return file size when succeed to shrink.
     */
    suspend fun queue(source: Uri, dest: Uri, progress: ((Int) -> Unit)? = null) = suspendCoroutine<Long> { continuation ->
        try {
            val request = Request(continuation, progress, source, dest, createWorkingFile())
            handler.post(object : Runnable {
                override fun run() {
                    if (isUnbinding()) {
                        handler.postDelayed(this, DELAY_RETRY_BIND)
                        return
                    }

                    try {
                        bindService()
                    } catch (e: IOException) {
                        Timber.tag(TAG).e(e, "Failed to bind service.")
                        request.deferred.resumeWithException(e)
                        return
                    }

                    queue.add(request)
                    if (sendMessenger != null) {
                        sendRequest(request)
                    }
                }
            })
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to create working file")
            continuation.resumeWithException(e)
        }
    }

    @Throws(IOException::class)
    private fun bindService() {
        Timber.tag(TAG).v("bind service.")
        if (binder?.isBinderAlive == true) {
            Timber.tag(TAG).v("Service bound.")
            return
        }
        val intent = Intent(context, MediaShrinkService::class.java)
        intent.putExtra(MediaShrinkService.EXTRA_WIDTH, width)
        intent.putExtra(MediaShrinkService.EXTRA_VIDEO_BITRATE, videoBitrate)
        intent.putExtra(MediaShrinkService.EXTRA_AUDIO_BITRATE, audioBitrate)
        intent.putExtra(MediaShrinkService.EXTRA_DURATION_LIMIT, durationLimit)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Timber.tag(TAG).e("bindService return false.")
            throw IOException("Failed to connect to MediaShrinkService.")
        }
        bound = true
    }

    private fun unbindServiceIfQueueIsEmpty() {
        if (bound && queue.isEmpty()) {
            Timber.tag(TAG).v("unbind service.")
            context.unbindService(connection)
            bound = false
            unbindInvoked = true
            sendMessenger = null
            receiveMessenger = null
        }
    }

    private fun unbindServiceIfQueueIsEmptyDelayed() {
        handler.removeCallbacks(unbindTask)
        handler.postDelayed(unbindTask, DELAY_UNBIND)
    }

    @SuppressLint("Assert")
    private fun sendRequest(r: Request): Boolean {
        assert(sendMessenger != null)
        assert(Looper.myLooper() == handler.looper)
        val m = Message.obtain(null, MediaShrinkService.REQUEST_SHRINK_MSGID)
        val data = Bundle()
        data.putParcelable(MediaShrinkService.REQUEST_SHRINK_INPUT_URI, r.source)
        data.putString(MediaShrinkService.REQUEST_SHRINK_OUTPUT_PATH, r.workingFile.absolutePath)
        m.data = data
        m.replyTo = receiveMessenger
        return try {
            sendMessenger!!.send(m)
            true
        } catch (e: RemoteException) {
            Timber.tag(TAG).e(e, "Failed to send request.")
            false
        }
    }

    private fun rebindServiceIfQueueIsNotEmpty() {
        if (!queue.isEmpty()) {
            if (isUnbinding()) {
                handler.postDelayed({ rebindServiceIfQueueIsNotEmpty() }, DELAY_RETRY_BIND)
                return
            }
            try {
                bindService()
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Failed to reconnect service.")
                val remains = queue.toTypedArray()
                queue.clear()
                for (r in remains) r.deferred.resumeWithException(e)
            }
        }
    }

    private fun isUnbinding(): Boolean {
        if (!unbindInvoked) return false
        if (binder?.pingBinder() != true) {
            unbindInvoked = false
            binder = null
            return false
        }
        Timber.tag(TAG).v("isUnbinding")
        return true
    }

    @Throws(IOException::class)
    private fun createWorkingFile(): File {
        workspace.mkdirs()
        if (!workspace.isDirectory) {
            throw IOException("Can not create workspace.")
        }
        return File.createTempFile(WORKING_FILE_PREFIX, null, workspace)
    }

    private fun cleanWorkspace() {
        Timber.tag(TAG).d("cleanWorkspace")
        val workspaceFiles = workspace.listFiles() ?: return
        for (file in workspaceFiles) {
            file.delete()
        }
    }

    private inner class MediaShrinkServiceConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Timber.tag(TAG).d("onServiceConnected.")
            handler.post {
                binder = service
                sendMessenger = Messenger(service)
                receiveMessenger = Messenger(ReceiveResultHandler(handler.looper))
                Timber.tag(TAG).v("send all requests. size:%d", queue.size)
                for (r in queue) {
                    if (!sendRequest(r)) { // rebind はイベントに任せる
                        break
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Timber.tag(TAG).e("onServiceDisconnected:%s", name)
            handler.post {
                val request = queue.poll()
                request?.deferred?.resumeWithException(RuntimeException("process killed."))
                request?.workingFile?.delete()
                bound = false
                sendMessenger = null
                receiveMessenger = null
                rebindServiceIfQueueIsNotEmpty()
            }
        }
    }

    private inner class ReceiveResultHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            Timber.tag(TAG).d("ReceiveResultHandler#handleMessage. what:%d", msg.what)
            when (msg.what) {
                MediaShrinkService.RESULT_COMPLETE_MSGID -> {
                    // 動画圧縮の途中でプロセスがkillされた際にゴミファイルが残らないように
                    // 圧縮中は作業ファイルに出力し圧縮完了後に出力先に移動するという手順を取る。
                    val request = queue.poll()!!
                    var inStream: InputStream? = null
                    var outStream: OutputStream? = null
                    try {
                        inStream = FileInputStream(request.workingFile)
                        outStream = context.contentResolver.openOutputStream(request.dest)
                                ?: throw IOException("output stream is null")
                        Utils.copy(inStream, outStream)
                        request.deferred.resume(request.workingFile.length())
                    } catch (e: IOException) {
                        Timber.tag(TAG).e(e, "Failed to rename temp file to dest file.")
                        request.deferred.resumeWithException(e)
                    } finally {
                        Utils.closeSilently(outStream)
                        Utils.closeSilently(inStream)
                    }
                    request.workingFile.delete()
                    unbindServiceIfQueueIsEmptyDelayed()
                }
                MediaShrinkService.RESULT_RECOVERABLE_ERROR_MSGID -> {
                    val request = queue.poll()!!
                    val ex = msg.data.getSerializable(MediaShrinkService.RESULT_RECOVERABLE_ERROR_EXCEPTION)
                            as Exception
                    request.deferred.resumeWithException(ex)
                    request.workingFile.delete()
                    unbindServiceIfQueueIsEmptyDelayed()
                }
                MediaShrinkService.RESULT_UNRECOVERABLE_ERROR_MSGID -> {
                    val request = queue.poll()!!
                    val ex = msg.data.getSerializable(MediaShrinkService.RESULT_UNRECOVERABLE_ERROR_EXCEPTION)
                            as Exception
                    request.deferred.resumeWithException(ex)
                    request.workingFile.delete()
                    unbindServiceIfQueueIsEmpty()
                }
                MediaShrinkService.RESULT_PROGRESS_MSGID -> {
                    val request = queue.peek()!!
                    request.progress?.invoke(msg.arg1)
                }
            }
        }
    }

    private inner class UnbindTask : Runnable {
        override fun run() {
            unbindServiceIfQueueIsEmpty()
        }
    }

    private data class Request(val deferred: Continuation<Long>,
                               val progress: ((Int) -> Unit)?,
                               val source: Uri,
                               val dest: Uri,
                               val workingFile: File)

    companion object {
        private const val TAG = "MediaShrinkQueue"
        private const val WORKING_FILE_PREFIX = "working_"

        private const val DELAY_RETRY_BIND = 1000L
        // delay unbind to reuse service
        private const val DELAY_UNBIND = 10 * 1000L

        fun isSupportedDevice(context: Context): Boolean = MediaShrink.isSupportedDevice(context)
    }
}
