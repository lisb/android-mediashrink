package com.lisb.android.mediashrink

import android.media.*
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class AudioShrink(private val extractor: MediaExtractor,
                  private val muxer: MediaMuxer,
                  private val errorCallback: UnrecoverableErrorCallback) {

    var bitRate = 0
    var onProgressListener: OnProgressListener? = null

    /**
     * エンコーダの設定に使うフォーマットを作成する。 <br></br>
     * [MediaMuxer.addTrack] には利用できない。
     */
    private fun createEncoderConfigurationFormat(origin: MediaFormat): MediaFormat {
        val channelCount = origin
                .getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val format = MediaFormat.createAudioFormat(CODEC,
                origin.getInteger(MediaFormat.KEY_SAMPLE_RATE), channelCount)
        // TODO ビットレートが元の値より大きくならないようにする
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE)
        Timber.tag(TAG).d("create audio encoder configuration format:%s", Utils.toString(format))
        return format
    }

    @Throws(DecodeException::class)
    private fun reencode(trackIndex: Int, newTrackIndex: Int?, listener: ReencodeListener?) {
        val originalFormat = extractor.getTrackFormat(trackIndex)
        Timber.tag(TAG).d("original format:%s", Utils.toString(originalFormat))
        val encoderConfigurationFormat = createEncoderConfigurationFormat(originalFormat)
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        // 進捗取得に利用
        val durationUs = originalFormat.getLong(MediaFormat.KEY_DURATION).toFloat()
        val startTimeNs = System.nanoTime()
        var deliverProgressCount: Long = 0
        try { // create encoder
            encoder = createEncoder(encoderConfigurationFormat)
            encoder.start()
            val encoderInputBuffers = encoder.inputBuffers
            var encoderOutputBuffers = encoder.outputBuffers
            val encoderOutputBufferInfo = MediaCodec.BufferInfo()
            // create decorder
            decoder = createDecoder(originalFormat)
            if (decoder == null) {
                Timber.tag(TAG).e("audio decoder not found.")
                throw DecodeException("audio decoder not found.")
            }
            decoder.start()
            val decoderInputBuffers = decoder.inputBuffers
            var decoderOutputBuffers = decoder.outputBuffers
            val decoderOutputBufferInfo = MediaCodec.BufferInfo()
            extractor.selectTrack(trackIndex)
            var extractorDone = false
            var decoderDone = false
            var pendingDecoderOutputBufferIndex = -1
            var lastExtracterOutputPts: Long = -1
            var lastDecoderOutputPts: Long = -1
            var lastEncoderOutputPts: Long = -1
            var sampleCount = 0
            while (true) { // read from extractor, write to decoder
                while (!extractorDone) {
                    val decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (decoderInputBufferIndex < 0) break
                    val decoderInputBuffer = decoderInputBuffers[decoderInputBufferIndex]
                    val size = extractor.readSampleData(decoderInputBuffer, 0)
                    // extractor.advance() より先に行うこと
                    val pts = extractor.sampleTime
                    var sampleFlags = extractor.sampleFlags
                    Timber.tag(TAG).v("audio extractor output. size:%d, sample time:%d, sample flags:%d", size,
                            pts, sampleFlags)
                    extractorDone = !extractor.advance()
                    if (extractorDone) {
                        Timber.tag(TAG).d("audio extractor: EOS, size:%d, sampleCount:%d",
                                size, sampleCount)
                        sampleFlags = sampleFlags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        if (sampleCount == 0) {
                            Timber.tag(TAG).e("no audio sample found.")
                            throw DecodeException("no audio sample found.")
                        }
                    }
                    if (size >= 0) {
                        if (lastExtracterOutputPts >= pts) {
                            Timber.tag(TAG).w("extractor output pts(%d) is smaller than last pts(%d)",
                                    pts, lastExtracterOutputPts)
                        } else {
                            lastExtracterOutputPts = pts
                        }
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, size, pts, sampleFlags)
                    } else if (extractorDone) {
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0, sampleFlags)
                    }
                    break
                }
                // read from decoder
                while (!decoderDone && pendingDecoderOutputBufferIndex == -1) {
                    val decoderOutputBufferIndex = decoder.dequeueOutputBuffer(decoderOutputBufferInfo,
                            TIMEOUT_USEC)
                    if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        Timber.tag(TAG).d("audio decoder: output buffers changed")
                        decoderOutputBuffers = decoder.outputBuffers
                        break
                    }
                    if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Timber.tag(TAG).d("audio decoder: output format changed. %s",
                                Utils.toString(decoder.outputFormat))
                        break
                    }

                    if (decoderOutputBufferIndex < 0) break

                    if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        decoder.releaseOutputBuffer(decoderOutputBufferIndex,
                                false)
                        break
                    }
                    pendingDecoderOutputBufferIndex = decoderOutputBufferIndex
                    val pts = decoderOutputBufferInfo.presentationTimeUs
                    Timber.tag(TAG).v("audio decoder output. time:%d, offset:%d, size:%d, flags:%d",
                            pts, decoderOutputBufferInfo.offset, decoderOutputBufferInfo.size,
                            decoderOutputBufferInfo.flags)
                    if (lastDecoderOutputPts >= pts) {
                        Timber.tag(TAG).w("decoder output pts(%d) is smaller than last pts(%d)",
                                pts, lastDecoderOutputPts)
                    } else {
                        lastDecoderOutputPts = pts
                    }
                    break
                }
                // write to encoder
                while (pendingDecoderOutputBufferIndex != -1) {
                    val encoderInputBufferIndex = encoder
                            .dequeueInputBuffer(TIMEOUT_USEC)
                    if (encoderInputBufferIndex < 0) {
                        break
                    }
                    val encoderInputBuffer = encoderInputBuffers[encoderInputBufferIndex]
                    val decoderOutputBuffer = decoderOutputBuffers[pendingDecoderOutputBufferIndex]
                            .duplicate()
                    decoderOutputBuffer
                            .position(decoderOutputBufferInfo.offset)
                    decoderOutputBuffer.limit(decoderOutputBufferInfo.offset
                            + decoderOutputBufferInfo.size)
                    encoderInputBuffer.position(0)
                    encoderInputBuffer.put(decoderOutputBuffer)
                    encoder.queueInputBuffer(encoderInputBufferIndex, 0,
                            decoderOutputBufferInfo.size,
                            decoderOutputBufferInfo.presentationTimeUs,
                            decoderOutputBufferInfo.flags)
                    decoder.releaseOutputBuffer(
                            pendingDecoderOutputBufferIndex, false)
                    if (decoderOutputBufferInfo.size != 0) {
                        sampleCount++
                    }
                    pendingDecoderOutputBufferIndex = -1
                    if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Timber.tag(TAG).d("audio decoder: EOS")
                        decoderDone = true
                    }
                    break
                }
                // write to muxer
                while (true) {
                    val encoderOutputBufferIndex = encoder.dequeueOutputBuffer(encoderOutputBufferInfo,
                                    TIMEOUT_USEC)
                    if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        Timber.tag(TAG).d("audio encoder: output buffers changed")
                        encoderOutputBuffers = encoder.outputBuffers
                        break
                    }
                    if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { // エンコーダに何か入力しないと、ここに来ないエンコーダがあるので注意。
                        if (listener != null) {
                            if (listener.onEncoderFormatChanged(encoder)) return
                        }
                        break
                    }
                    if (encoderOutputBufferIndex < 0) {
                        break
                    }
                    val encoderOutputBuffer = encoderOutputBuffers[encoderOutputBufferIndex]
                    if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encoder.releaseOutputBuffer(encoderOutputBufferIndex,
                                false)
                        break
                    }
                    val pts = encoderOutputBufferInfo.presentationTimeUs
                    Timber.tag(TAG).v("audio encoder output. time:%d, offset:%d, size:%d, flags:%d",
                            pts, encoderOutputBufferInfo.offset, encoderOutputBufferInfo.size,
                            encoderOutputBufferInfo.flags)
                    if (encoderOutputBufferInfo.size != 0) {
                        if (lastEncoderOutputPts >= pts) {
                            Timber.tag(TAG).w("encoder output pts(%d) is smaller than last pts(%d)",
                                    pts, lastEncoderOutputPts)
                        } else {
                            if (newTrackIndex != null) {
                                muxer.writeSampleData(newTrackIndex,
                                        encoderOutputBuffer,
                                        encoderOutputBufferInfo)
                                lastEncoderOutputPts = pts
                                // 進捗更新
                                if ((System.nanoTime() - startTimeNs) / 1000 / 1000 > UPDATE_PROGRESS_INTERVAL_MS
                                        * (deliverProgressCount + 1)) {
                                    deliverProgressCount++
                                    onProgressListener?.onProgress((encoderOutputBufferInfo.presentationTimeUs * 100 / durationUs).toInt())
                                }
                            }
                        }
                    }
                    if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Timber.tag(TAG).d("audio encoder: EOS")
                        return
                    }
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                    break
                }
            }
        } catch (e: DecodeException) { // recoverable error
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "unrecoverable error occured on audio shrink.")
            errorCallback.onUnrecoverableError(e)
        } finally {
            if (encoder != null) {
                encoder.stop()
                encoder.release()
            }
            if (decoder != null) {
                decoder.stop()
                decoder.release()
            }
            extractor.unselectTrack(trackIndex)
        }
    }

    @Throws(DecodeException::class)
    fun createOutputFormat(trackIndex: Int): MediaFormat {
        val formatRef = AtomicReference<MediaFormat>()
        reencode(trackIndex, null, object : ReencodeListener {
            override fun onEncoderFormatChanged(encoder: MediaCodec): Boolean {
                Timber.tag(TAG).d("audio encoder: output format changed. %s",
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
    private fun createDecoder(format: MediaFormat): MediaCodec? {
        val mimeType = format.getString(MediaFormat.KEY_MIME)
        val codecName = Utils.selectCodec(mimeType, false).name
        return try {
            val decoder: MediaCodec? = MediaCodec.createByCodecName(codecName)
            if (decoder != null) {
                decoder.configure(format, null, null, 0)
                Timber.tag(TAG).d("audio decoder:%s", decoder.name)
            }
            decoder
        } catch (e: IOException) { // later Lollipop.
            val detailMessage = "audio decoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw DecoderCreationException(detailMessage, e)
        } catch (e: IllegalStateException) {
            val detailMessage = "audio decoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw DecoderCreationException(detailMessage, e)
        }
    }

    @Throws(EncoderCreationException::class)
    private fun createEncoder(format: MediaFormat): MediaCodec {
        val codecName = Utils.selectCodec(CODEC, true).name
        return try {
            val encoder = MediaCodec.createByCodecName(codecName)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Timber.tag(TAG).d("audio encoder:%s", encoder.name)
            encoder
        } catch (e: IOException) { // later Lollipop.
            val detailMessage = "audio encoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw EncoderCreationException(detailMessage, e)
        } catch (e: IllegalStateException) { // TODO Change Detail Message If minSDKVersion > 21
            val detailMessage = "audio encoder cannot be created. codec-name:$codecName"
            Timber.tag(TAG).e(e, detailMessage)
            throw EncoderCreationException(detailMessage, e)
        }
    }

    private interface ReencodeListener {
        /**
         * @return if stop or not
         */
        fun onEncoderFormatChanged(encoder: MediaCodec): Boolean
    }

    companion object {
        private const val TAG = "AudioShrink"
        private const val TIMEOUT_USEC: Long = 250
        private const val CODEC = "audio/mp4a-latm"
        // Because AACObjectHE of some encoder(ex. OMX.google.aac.encoder) is buggy,
        // Don't use AACObjectHE.
        //
        // bug example:
        // - specify mono sound but output stereo sound.
        // - output wrong time_base_codec.
        private const val AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
        private const val UPDATE_PROGRESS_INTERVAL_MS = 3 * 1000.toLong()
    }

}