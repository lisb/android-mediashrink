package com.lisb.android.mediashrink

import android.media.MediaCodec
import android.os.Process
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MediaCodec#stopを行い、時間内に終了しないようならプロセスを強制終了する
 */
fun MediaCodec.stopWithTimeout() {
    val job = GlobalScope.launch {
        delay(STOP_TIMEOUT_MS)
        if (!isActive) return@launch
        Timber.tag(TAG).e("MediaCodec#stop timeout")
        Process.killProcess(Process.myPid())
    }
    try {
        stop()
    } finally {
        job.cancel()
    }
}

private const val TAG = "MediaCodecExt"
private const val STOP_TIMEOUT_MS = 10_000L