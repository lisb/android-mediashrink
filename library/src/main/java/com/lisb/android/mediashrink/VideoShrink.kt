package com.lisb.android.mediashrink

import android.media.*
import android.os.Build
import android.os.Environment
import android.view.Surface
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class VideoShrink(private val extractor: MediaExtractor,
                  metadataRetriever: MediaMetadataRetriever,
                  private val muxer: MediaMuxer,
                  private val errorCallback: UnrecoverableErrorCallback) {
    private val rotation = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
    var bitRate = 0

    /**
     * 指定必須
     *
     * Warning: Nexus 7 では決まった幅(640, 384など)でないとエンコード結果がおかしくなる。
     * セットした値で正しくエンコードできるかテストすること。
     */
    var width = -1
        set(value) {
            require(!(width > 0 && width % 16 > 0)) { "Only multiples of 16 is supported." }
            field = value
        }
    var onProgressListener: OnProgressListener? = null

    /**
     * エンコーダの設定に使うフォーマットを作成する。 <br></br>
     * [MediaMuxer.addTrack] には利用できない。
     */
    @Throws(DecodeException::class)
    private fun createEncoderConfigurationFormat(origin: MediaFormat): MediaFormat {
        val originWidth: Int
        val originHeight: Int
        if (rotation == 90 || rotation == 270) {
            originWidth = origin.getInteger(MediaFormat.KEY_HEIGHT)
            originHeight = origin.getInteger(MediaFormat.KEY_WIDTH)
        } else {
            originWidth = origin.getInteger(MediaFormat.KEY_WIDTH)
            originHeight = origin.getInteger(MediaFormat.KEY_HEIGHT)
        }
        val widthRatio = width.toFloat() / originWidth
        // アスペクト比を保ったまま、16の倍数になるように(エンコードの制限) width, height を指定する。
        val height = getMultipliesOf16(originHeight * widthRatio)
        val format = MediaFormat.createVideoFormat(ENCODE_MIMETYPE, width,
                height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        // TODO ビットレートが元の値より大きくならないようにする
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAMERATE)
        Timber.tag(TAG).d("create encoder configuration format:%s, rotation:%d", format, rotation)
        return format
    }

    /**
     * 指定された数字に最も近い16の倍数の値を返す
     */
    private fun getMultipliesOf16(number: Float): Int {
        val round = number.roundToInt()
        val rem = round % 16
        return if (rem < 8) {
            round - rem
        } else {
            round + 16 - rem
        }
    }

    private interface ReencodeListener {
        /**
         * @return if stop or not
         */
        fun onEncoderFormatChanged(encoder: MediaCodec): Boolean
    }

    @Throws(DecodeException::class)
    private fun reencode(trackIndex: Int, newTrackIndex: Int?, listener: ReencodeListener?) {
        val currentFormat = extractor.getTrackFormat(trackIndex)
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var outputSurface: OutputSurface? = null
        var inputSurface: InputSurface? = null
        var extractorDone = false
        var decoderDone = false
        var snapshotIndex = 0
        // 進捗取得に利用
        val durationUs = currentFormat.getLong(MediaFormat.KEY_DURATION).toFloat()
        val startTimeNs = System.nanoTime()
        var deliverProgressCount: Long = 0
        try {
            val encoderConfigurationFormat = createEncoderConfigurationFormat(currentFormat)
            encoder = createEncoder(encoderConfigurationFormat)
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()
            val encoderOutputBufferInfo = MediaCodec.BufferInfo()
            var encoderOutputBuffers = encoder.outputBuffers

            val snapshotOptions: SnapshotOptions?
            val snapshotDuration: Long
            if (DEBUG) {
                snapshotOptions = SnapshotOptions()
                snapshotOptions.width = encoderConfigurationFormat.getInteger(MediaFormat.KEY_WIDTH)
                snapshotOptions.height = encoderConfigurationFormat.getInteger(MediaFormat.KEY_HEIGHT)
                snapshotDuration = currentFormat.getLong(MediaFormat.KEY_DURATION) / NUMBER_OF_SNAPSHOT
            } else {
                snapshotOptions = null
                snapshotDuration = 0
            }
            // lollipop から Surface への出力時に自動で回転して出力するようになったため、こちら側では回転を行わない。
            // https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
            outputSurface = OutputSurface(if (Build.VERSION.SDK_INT >= 21) 0f else (-rotation).toFloat())
            decoder = createDecoder(currentFormat, outputSurface.surface!!)
            if (decoder == null) {
                Timber.tag(TAG).e("video decoder not found.")
                throw DecodeException("video decoder not found.")
            }
            decoder.start()
            val decoderInputBuffers = decoder.inputBuffers
            val decoderOutputBufferInfo = MediaCodec.BufferInfo()
            extractor.selectTrack(trackIndex)
            var frameCount = 0
            var lastDecodePresentationTimeMs: Long = 0
            while (true) {
                while (!extractorDone) {
                    val decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (decoderInputBufferIndex < 0) break
                    val decoderInputBuffer = decoderInputBuffers[decoderInputBufferIndex]
                    val size = extractor.readSampleData(decoderInputBuffer, 0)
                    // extractor.advance() より先に行うこと
                    val sampleTime = extractor.sampleTime
                    var sampleFlags = extractor.sampleFlags
                    Timber.tag(TAG).v("video extractor output. size:%d, sample time:%d, sample flags:%d",
                            size, sampleTime, sampleFlags)
                    extractorDone = !extractor.advance()
                    if (extractorDone) {
                        Timber.tag(TAG).d("video extractor: EOS, size:%d", size)
                        sampleFlags = sampleFlags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    }
                    if (size >= 0) {
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, size, sampleTime,
                                sampleFlags)
                    } else if (extractorDone) {
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0, sampleFlags)
                    }
                    break
                }
                while (!decoderDone) {
                    val decoderOutputBufferIndex = decoder.dequeueOutputBuffer(
                            decoderOutputBufferInfo, TIMEOUT_USEC)
                    if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Timber.tag(TAG).d("video decoder: output format changed. %s",
                                Utils.toString(decoder.outputFormat))
                    }
                    if (decoderOutputBufferIndex < 0) {
                        break
                    }
                    Timber.tag(TAG).v("video decoder output. time:%d, offset:%d, size:%d, flags:%d",
                            decoderOutputBufferInfo.presentationTimeUs,
                            decoderOutputBufferInfo.offset, decoderOutputBufferInfo.size,
                            decoderOutputBufferInfo.flags)
                    if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        decoder.releaseOutputBuffer(decoderOutputBufferIndex,
                                false)
                        break
                    }
                    val render = decoderOutputBufferInfo.size != 0
                    decoder.releaseOutputBuffer(decoderOutputBufferIndex,
                            render)
                    if (decoderOutputBufferInfo.size != 0) {
                        frameCount++
                    }
                    if (render) {
                        if (snapshotOptions != null && snapshotIndex * snapshotDuration <= decoderOutputBufferInfo.presentationTimeUs) {
                            snapshotOptions.file = getSnapshotFile(
                                    snapshotIndex,
                                    decoderOutputBufferInfo.presentationTimeUs)
                            outputSurface.drawNewImage(snapshotOptions)
                            snapshotIndex++
                        } else {
                            outputSurface.drawNewImage(null)
                        }
                        val presentaionTimeMs = decoderOutputBufferInfo.presentationTimeUs / 1000
                        if (lastDecodePresentationTimeMs <= 0
                                || presentaionTimeMs
                                - lastDecodePresentationTimeMs >= MAX_FRAME_INTERVAL_MS) { // lastDecodePresentaitonTimeMs
// が0以下の場合は特殊なケースになりそうなので間引く対象から外す。
                            inputSurface
                                    .setPresentationTime(decoderOutputBufferInfo.presentationTimeUs * 1000)
                            inputSurface.swapBuffers()
                            lastDecodePresentationTimeMs = presentaionTimeMs
                        } else {
                            Timber.tag(TAG).i("Frame removed because frame interval is too short. current:%d, last:%d",
                                    presentaionTimeMs, lastDecodePresentationTimeMs)
                        }
                    }
                    if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (frameCount == 0) {
                            Timber.tag(TAG).e("no video frame found.")
                            throw DecodeException("no video frame found.")
                        }
                        Timber.tag(TAG).d("video decoder: EOS")
                        decoderDone = true
                        encoder.signalEndOfInputStream()
                    }
                    break
                }
                while (true) {
                    val encoderOutputBufferIndex = encoder.dequeueOutputBuffer(encoderOutputBufferInfo,
                            TIMEOUT_USEC)
                    if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        Timber.tag(TAG).d("video encoder: output buffers changed")
                        encoderOutputBuffers = encoder.outputBuffers
                        break
                    }
                    if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (listener != null) {
                            if (listener.onEncoderFormatChanged(encoder)) {
                                return
                            }
                        }
                        break
                    }
                    if (encoderOutputBufferIndex < 0) {
                        break
                    }
                    val encoderOutputBuffer = encoderOutputBuffers[encoderOutputBufferIndex]
                    Timber.tag(TAG).v("video encoder output. time:%d, offset:%d, size:%d, flags:%d",
                            encoderOutputBufferInfo.presentationTimeUs,
                            encoderOutputBufferInfo.offset, encoderOutputBufferInfo.size,
                            encoderOutputBufferInfo.flags)
                    if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { // エンコーダに何か入力しないと、ここに来ないエンコーダがあるので注意。
                        encoder.releaseOutputBuffer(encoderOutputBufferIndex,
                                false)
                        break
                    }
                    if (encoderOutputBufferInfo.size != 0) {
                        if (newTrackIndex != null) {
                            muxer.writeSampleData(newTrackIndex,
                                    encoderOutputBuffer,
                                    encoderOutputBufferInfo)
                            // 進捗更新
                            if ((System.nanoTime() - startTimeNs) / 1000 / 1000 > UPDATE_PROGRESS_INTERVAL_MS
                                    * (deliverProgressCount + 1)) {
                                deliverProgressCount++
                                onProgressListener?.onProgress((encoderOutputBufferInfo.presentationTimeUs * 100 / durationUs).toInt())
                            }
                        }
                    }
                    if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Timber.tag(TAG).d("video encoder: EOS")
                        return
                    }
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                    break
                }
            }
        } catch (e: DecodeException) { // recoverable error
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Unrecoverable error occured on video shrink.")
            errorCallback.onUnrecoverableError(e)
        } finally {
            if (encoder != null) {
                encoder.stop()
                encoder.release()
            }
            inputSurface?.release()
            if (decoder != null) {
                decoder.stop()
                decoder.release()
            }
            outputSurface?.release()
            extractor.unselectTrack(trackIndex)
        }
    }

    private fun getSnapshotFile(snapshotIndex: Int, presentationTimeUs: Long): File {
        return File(Environment.getExternalStorageDirectory(),
                "$SNAPSHOT_FILE_PREFIX${snapshotIndex}_${presentationTimeUs / 1000}.$SNAPSHOT_FILE_EXTENSION")
    }

    @Throws(DecodeException::class)
    fun createOutputFormat(trackIndex: Int): MediaFormat {
        val formatRef = AtomicReference<MediaFormat>()
        reencode(trackIndex, null, object : ReencodeListener {
            override fun onEncoderFormatChanged(encoder: MediaCodec): Boolean {
                Timber.tag(TAG).d("video encoder: output format changed. %s",
                        Utils.toString(encoder.outputFormat))
                formatRef.set(encoder.outputFormat)
                return true
            }
        })
        return formatRef.get()
    }

    @Throws(DecodeException::class)
    fun shrink(trackIndex: Int, newTrackIndex: Int) {
        reencode(trackIndex, newTrackIndex, null)
    }

    @Throws(DecoderCreationException::class)
    private fun createDecoder(format: MediaFormat, surface: Surface): MediaCodec? {
        val mimeType = format.getString(MediaFormat.KEY_MIME)
        val codec = Utils.selectCodec(mimeType, false)
        if (codec == null) {
            val detailMessage = "video decoder codec is not found. mime-type:$mimeType"
            Timber.tag(TAG).e(detailMessage)
            throw DecoderCreationException(detailMessage)
        }
        val codecName = codec.name
        return try {
            val decoder: MediaCodec? = MediaCodec.createByCodecName(codecName)
            if (decoder != null) {
                decoder.configure(format, surface, null, 0)
                Timber.tag(TAG).d("video decoder:%s", decoder.name)
            }
            decoder
        } catch (e: IOException) { // later Lollipop.
            val detailMessage = "video decoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw DecoderCreationException(detailMessage, e)
        } catch (e: IllegalStateException) {
            val detailMessage = "video decoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw DecoderCreationException(detailMessage, e)
        }
    }

    @Throws(EncoderCreationException::class)
    private fun createEncoder(format: MediaFormat): MediaCodec {
        val codec = Utils.selectCodec(ENCODE_MIMETYPE, true)
        if (codec == null) {
            val detailMessage = "video encoder codec is not found. mime-type:$ENCODE_MIMETYPE"
            Timber.tag(TAG).e(detailMessage)
            throw EncoderCreationException(detailMessage)
        }
        val codecName = codec.name
        return try {
            val encoder = MediaCodec.createByCodecName(codecName)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Timber.tag(TAG).d("video encoder:%s", encoder.name)
            encoder
        } catch (e: IOException) { // later Lollipop.
            val detailMessage = "video encoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw EncoderCreationException(detailMessage, e)
        } catch (e: IllegalStateException) { // TODO Change Detail Message If minSDKVersion > 21
            val detailMessage = "video encoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw EncoderCreationException(detailMessage, e)
        }
    }

    companion object {
        private const val TAG = "VideoShrink"
        private const val DEBUG = false // デバッグ用にスナップショットを出力する
        private const val ENCODE_MIMETYPE = "video/avc"
        private const val TIMEOUT_USEC: Long = 250
        private const val I_FRAME_INTERVAL = 5
        // フレームレートはビットレート/フレームレートでフレーム一枚あたりのビット数を割り出すために存在する。
// そのため厳密に実際の動画のレートに合わせる必要はない。
// ただし実際のフレーム数より少なく設定してしまうとファイル数が想定より大きくなってしまうので、
// MAX_FRAME_INTERVAL_MSEC をこえた場合、間引く。
        private const val FRAMERATE = 30
        private const val MAX_FRAME_INTERVAL_MS = 1000f / (FRAMERATE * 1.1f)
        private const val SNAPSHOT_FILE_PREFIX = "android-videoshrink-snapshot"
        private const val SNAPSHOT_FILE_EXTENSION = "jpg"
        private const val NUMBER_OF_SNAPSHOT = 10
        private const val UPDATE_PROGRESS_INTERVAL_MS = 3 * 1000.toLong()
    }

}