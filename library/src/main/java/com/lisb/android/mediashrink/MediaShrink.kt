package com.lisb.android.mediashrink

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Looper
import timber.log.Timber
import java.io.IOException

/**
 * ## WARN: [MediaShrink.shrink] のスレッドについて制約
 * 以下の制約を守らないと圧縮がロックされたままになる。
 * - [MediaShrink] の呼び出し元のスレッドは [Looper] を持たない [Thread] である必要がある
 * - [Looper.getMainLooper] の [Looper] が周り続けている必要がある
 *
 * ### 理由
 * ビデオの圧縮で利用している [SurfaceTexture] のコールバックが呼び出されるスレッドが以下のようになっている。
 * - スレッドが [Looper] を保つ場合、そのスレッド
 * - 持たない場合、 メインスレッド
 *
 * そのため、上記の制約を守らないとコールバックが呼び出されない。
 *
 */
internal class MediaShrink(private val context: Context) {
    /**
     * 指定必須。
     *
     * Warning: Nexus 7 では決まった幅(640, 384など)でないとエンコード結果がおかしくなる。
     * セットした値で正しくエンコードできるかテストすること。
     */
    var width = -1
    /**
     * ビデオの最大の長さ。 この長さを越えるビデオのエンコードは行わない。0以下の時、無視される。
     */
    var durationLimit: Long = -1
    /**
     * 設定必須
     */
    var audioBitRate = 0
    /**
     * 設定必須
     */
    var videoBitRate = 0
    var onProgressListener: OnProgressListener? = null

    @Throws(IOException::class, TooMovieLongException::class)
    fun shrink(inputUri: Uri, outputPath: String, errorCallback: UnrecoverableErrorCallback) {
        var extractor: MediaExtractor? = null
        var metadataRetriever: MediaMetadataRetriever? = null
        var muxer: MediaMuxer? = null
        var videoShrink: VideoShrink? = null
        var audioShrink: AudioShrink? = null
        try {
            extractor = MediaExtractor()
            metadataRetriever = MediaMetadataRetriever()
            try {
                extractor.setDataSource(context, inputUri, null)
                metadataRetriever.setDataSource(context, inputUri)
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Failed to read input.")
                throw IOException("Failed to read input.", e)
            }
            checkLength(metadataRetriever)
            var maxProgress = 0
            var progress = 0
            // プログレスの計算のため、圧縮するトラックの数を前もって数える
            var videoTrack: Int? = null
            var audioTrack: Int? = null
            var i = 0
            val length = extractor.trackCount
            while (i < length) {
                val format = extractor.getTrackFormat(i)
                Timber.tag(TAG).d("track[%d] format: %s", i, Utils.toString(format))
                if (isVideoFormat(format)) {
                    if (videoTrack != null) { // MediaMuxer がビデオ、オーディオそれぞれ1つずつしか含めることができないため。
                        Timber.tag(TAG).w("drop track. support one video track only. track:%d", i)
                        i++
                        continue
                    }
                    videoTrack = i
                    maxProgress += PROGRESS_ADD_TRACK + PROGRESS_WRITE_CONTENT
                } else if (isAudioFormat(format)) {
                    if (audioTrack != null) { // MediaMuxer がビデオ、オーディオそれぞれ1つずつしか含めることができないため。
                        Timber.tag(TAG).w("drop track. support one audio track only. track:%d", i)
                        i++
                        continue
                    }
                    audioTrack = i
                    maxProgress += PROGRESS_ADD_TRACK + PROGRESS_WRITE_CONTENT
                } else {
                    Timber.tag(TAG).e("drop track. unsupported format. track:%d, format:%s",
                            i, Utils.toString(format))
                }
                i++
            }
            try {
                muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                // トラックの作成。
                var newVideoTrack: Int? = null
                if (videoTrack != null) {
                    videoShrink = VideoShrink(extractor, metadataRetriever, muxer, errorCallback)
                    videoShrink.setWidth(width)
                    videoShrink.setBitRate(videoBitRate)
                    newVideoTrack = muxer.addTrack(videoShrink.createOutputFormat(videoTrack))
                    progress += PROGRESS_ADD_TRACK
                    deliverProgress(progress, maxProgress)
                }
                var newAudioTrack: Int? = null
                if (audioTrack != null) {
                    audioShrink = AudioShrink(extractor, muxer, errorCallback)
                    audioShrink.setBitRate(audioBitRate)
                    newAudioTrack = muxer.addTrack(audioShrink.createOutputFormat(audioTrack))
                    progress += PROGRESS_ADD_TRACK
                    deliverProgress(progress, maxProgress)
                }
                muxer.start()
                // コンテンツの作成
                if (videoShrink != null) { // ビデオ圧縮の進捗を詳細に取れるようにする
                    val currentProgress = progress
                    val currentMaxProgress = maxProgress
                    videoShrink.setOnProgressListener(OnProgressListener {
                        deliverProgress(currentProgress + it * PROGRESS_WRITE_CONTENT / 100,
                                currentMaxProgress)
                    })
                    videoShrink.shrink(videoTrack!!, newVideoTrack!!)
                    progress += PROGRESS_WRITE_CONTENT
                    deliverProgress(progress, maxProgress)
                }
                if (audioShrink != null) { // オーディオ圧縮の進捗を詳細に取れるようにする
                    val currentProgress = progress
                    val currentMaxProgress = maxProgress
                    audioShrink.setOnProgressListener(OnProgressListener {
                        deliverProgress(currentProgress + it * PROGRESS_WRITE_CONTENT / 100,
                                currentMaxProgress)
                    })
                    audioShrink.shrink(audioTrack!!, newAudioTrack!!)
                    progress += PROGRESS_WRITE_CONTENT
                    deliverProgress(progress, maxProgress)
                }
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Failed to write output.")
                throw IOException("Failed to write output.", e)
            } catch (e: Throwable) { // muxer はきちんと書き込みせずに閉じると RuntimeException を発行するので
// muxer を開いたあとは全ての例外が unrecoverable。
                Timber.tag(TAG).e(e, "Unrecoverable error occurred on media shrink.")
                errorCallback.onUnrecoverableError(e)
            } finally {
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                }
            }
        } catch (e: IOException) { // recoverable error
            throw e
        } catch (e: TooMovieLongException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Unrecoverable error occurred on media shrink.")
            errorCallback.onUnrecoverableError(e)
        } finally {
            try {
                extractor?.release()
                metadataRetriever?.release()
            } catch (e: RuntimeException) {
                Timber.tag(TAG).e(e, "Failed to finalize shrink.")
                errorCallback.onUnrecoverableError(e)
            }
        }
    }

    private fun deliverProgress(progress: Int, maxProgress: Int) {
        onProgressListener?.onProgress(progress * 100 / maxProgress)
    }

    @Throws(TooMovieLongException::class)
    private fun checkLength(metadataRetriever: MediaMetadataRetriever) {
        if (durationLimit <= 0) return
        val durationSec = (metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() / 1000
        if (durationSec > durationLimit) {
            Timber.tag(TAG).e("Movie duration(%d sec) is longer than duration limit(%d sec).",
                    durationSec, durationLimit)
            throw TooMovieLongException("Movie duration ($durationSec sec)is longer than duration limit($durationLimit sec). ")
        }
    }

    private fun isVideoFormat(format: MediaFormat): Boolean {
        return format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    }

    private fun isAudioFormat(format: MediaFormat): Boolean {
        return format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    companion object {
        private const val TAG = "MediaShrink"
        private const val PROGRESS_ADD_TRACK = 10
        private const val PROGRESS_WRITE_CONTENT = 40
        fun isSupportedDevice(context: Context?): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return false
            }
            return OpenglUtils.supportsOpenglEs2(context)
        }
    }

}