package com.lisb.android.mediashrink

import android.annotation.SuppressLint
import android.media.*
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
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
    private fun createEncoderConfigurationFormat(decoderOutputFormat: MediaFormat): MediaFormat {
        val channelCount = decoderOutputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sampleRate = decoderOutputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val format = MediaFormat.createAudioFormat(ENCODE_MIMETYPE, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE)
        Timber.tag(TAG).d("create audio encoder configuration format:%s, decoderOutputFormat:%s",
                Utils.toString(format), Utils.toString(decoderOutputFormat))
        return format
    }

    @Throws(DecodeException::class)
    private fun reencode(trackIndex: Int, newTrackIndex: Int?, listener: ReencodeListener?) {
        Timber.tag(TAG).d("reencode. trackIndex:%d, newTrackIndex:%d, isNull(listener):%b",
                trackIndex, newTrackIndex, listener == null)
        // TODO 既存の音声のビットレートが指定された　bitRate よりも低い場合、圧縮せずにそのまま利用する
        val originalFormat = extractor.getTrackFormat(trackIndex)
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        // 進捗取得に利用
        val durationUs = originalFormat.getLong(MediaFormat.KEY_DURATION).toFloat()
        val startTimeNs = System.nanoTime()
        var deliverProgressCount: Long = 0
        try {
            val encoderOutputBufferInfo = MediaCodec.BufferInfo()
            // create decorder
            decoder = createDecoder(originalFormat)
            if (decoder == null) {
                Timber.tag(TAG).e("audio decoder not found.")
                throw DecodeException("audio decoder not found.")
            }
            decoder.start()
            val decoderOutputBufferInfo = MediaCodec.BufferInfo()
            extractor.selectTrack(trackIndex)
            var extractorDone = false
            var decoderDone = false
            var decoderOutputBuffer: ByteBuffer? = null // エンコーダに入力されていないデータが残っているデコーダの出力
            var decoderOutputBufferIndex: Int = -1
            var lastExtractorOutputPts: Long = -1
            var lastDecoderOutputPts: Long = -1
            var lastEncoderOutputPts: Long = -1
            var sampleCount = 0
            while (true) { // read from extractor, write to decoder
                while (!extractorDone) {
                    val decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (decoderInputBufferIndex < 0) {
                        break
                    }
                    val decoderInputBuffer = requireNotNull(decoder.getInputBuffer(decoderInputBufferIndex))
                    val size = extractor.readSampleData(decoderInputBuffer, 0)
                    // extractor.advance() より先に行うこと
                    val pts = extractor.sampleTime
                    var sampleFlags = extractor.sampleFlags
                    Timber.tag(TAG).v("audio extractor output. buffer.pos:%d, buffer.limit:%d, size:%d, sample time:%d, sample flags:%d",
                            decoderInputBuffer.position(), decoderInputBuffer.limit(), size, pts, sampleFlags)
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
                        if (lastExtractorOutputPts >= pts) {
                            Timber.tag(TAG).w("extractor output pts(%d) is smaller than last pts(%d)",
                                    pts, lastExtractorOutputPts)
                        } else {
                            lastExtractorOutputPts = pts
                        }
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, size, pts, sampleFlags)
                    } else if (extractorDone) {
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0, sampleFlags)
                    }
                    break
                }
                // read from decoder
                while (!decoderDone && decoderOutputBuffer == null) {
                    @SuppressLint("WrongConstant")
                    decoderOutputBufferIndex = decoder.dequeueOutputBuffer(
                            decoderOutputBufferInfo, TIMEOUT_USEC)
                    Timber.tag(TAG).v("audio decoder output. bufferIndex:%d, time:%d, offset:%d, size:%d, flags:%d",
                            decoderOutputBufferIndex,
                            decoderOutputBufferInfo.presentationTimeUs,
                            decoderOutputBufferInfo.offset,
                            decoderOutputBufferInfo.size,
                            decoderOutputBufferInfo.flags)
                    if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Timber.tag(TAG).d("audio decoder: output format changed. %s",
                                Utils.toString(decoder.outputFormat))
                        break
                    }

                    if (decoderOutputBufferIndex < 0) break

                    // NOTE: flags が MediaCodec.BUFFER_FLAG_CODEC_CONFIG かつ BUFFER_FLAG_END_OF_STREAM
                    // のときがあるので注意。
                    if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        decoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                        break
                    }
                    decoderOutputBuffer = requireNotNull(decoder.getOutputBuffer(decoderOutputBufferIndex))
                    if (lastDecoderOutputPts >= decoderOutputBufferInfo.presentationTimeUs) {
                        Timber.tag(TAG).w("decoder output pts(%d) is smaller than last pts(%d)",
                                decoderOutputBufferInfo.presentationTimeUs, lastDecoderOutputPts)
                    } else {
                        lastDecoderOutputPts = decoderOutputBufferInfo.presentationTimeUs
                    }
                    break
                }

                // write to encoder
                while (decoderOutputBuffer != null) {
                    if (encoder == null) {
                        // デコーダの制限などでデコード前とデコード後でサンプリングレートやチャネル数が違うことがあるので
                        // Encoder の作成を遅延する。
                        val encoderConfigurationFormat = createEncoderConfigurationFormat(
                                decoder.outputFormat)
                        encoder = createEncoder(encoderConfigurationFormat)
                        encoder.start()
                    }

                    // TODO
                    // デコード後、デコード前と比べてチャネル数やサンプリングレートが上がってしまっていた場合でも
                    // チャネル数やサンプリングレートをデコード前の値に落として圧縮できるようにする

                    val encoderInputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (encoderInputBufferIndex < 0) {
                        break
                    }
                    val encoderInputBuffer = requireNotNull(encoder.getInputBuffer(encoderInputBufferIndex))
                    encoderInputBuffer.clear()
                    if (encoderInputBuffer.remaining() >= decoderOutputBufferInfo.size) {
                        Timber.tag(TAG).v("audio encoder input. bufferIndex:%d, time:%d, offset:%d, size:%d, flags:%d",
                                encoderInputBufferIndex,
                                decoderOutputBufferInfo.presentationTimeUs,
                                decoderOutputBufferInfo.offset,
                                decoderOutputBufferInfo.size,
                                decoderOutputBufferInfo.flags)
                        decoderOutputBuffer.position(decoderOutputBufferInfo.offset)
                        decoderOutputBuffer.limit(decoderOutputBufferInfo.offset + decoderOutputBufferInfo.size)
                        encoderInputBuffer.put(decoderOutputBuffer)
                        encoder.queueInputBuffer(encoderInputBufferIndex,
                                0,
                                decoderOutputBufferInfo.size,
                                decoderOutputBufferInfo.presentationTimeUs,
                                decoderOutputBufferInfo.flags)
                        decoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                        if (decoderOutputBufferInfo.size != 0) {
                            sampleCount++
                        }
                        decoderOutputBuffer = null
                        if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Timber.tag(TAG).d("audio decoder: EOS")
                            decoderDone = true
                        }
                    } else {
                        // エンコーダの入力バッファがデコーダの出力より小さい
                        val inputBufferRemaining = encoderInputBuffer.remaining()
                        val pts = decoderOutputBufferInfo.presentationTimeUs
                        val flags = decoderOutputBufferInfo.flags.and(MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        Timber.tag(TAG).v("multi-phase audio encoder input. bufferIndex:%d, time:%d, offset:%d, size:%d, flags:%d",
                                encoderInputBufferIndex,
                                pts,
                                decoderOutputBufferInfo.offset,
                                inputBufferRemaining,
                                flags)
                        decoderOutputBuffer.position(decoderOutputBufferInfo.offset)
                        decoderOutputBuffer.limit(decoderOutputBufferInfo.offset + inputBufferRemaining)
                        encoderInputBuffer.put(decoderOutputBuffer)

                        encoder.queueInputBuffer(encoderInputBufferIndex,
                                0,
                                inputBufferRemaining,
                                pts,
                                flags)
                        sampleCount++

                        decoderOutputBufferInfo.offset += inputBufferRemaining
                        decoderOutputBufferInfo.size -= inputBufferRemaining
                        val outputSamplingRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val outputChannelCount = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val duration = (inputBufferRemaining / 2 / outputChannelCount) * 1000L * 1000L / outputSamplingRate
                        decoderOutputBufferInfo.presentationTimeUs += duration
                    }
                    break
                }
                // write to muxer
                while (encoder != null) {
                    val encoderOutputBufferIndex = encoder.dequeueOutputBuffer(
                            encoderOutputBufferInfo, TIMEOUT_USEC)
                    Timber.tag(TAG).v("audio encoder output. bufferIndex:%d, time:%d, offset:%d, size:%d, flags:%d",
                            encoderOutputBufferIndex,
                            encoderOutputBufferInfo.presentationTimeUs,
                            encoderOutputBufferInfo.offset,
                            encoderOutputBufferInfo.size,
                            encoderOutputBufferInfo.flags)

                    if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { // エンコーダに何か入力しないと、ここに来ないエンコーダがあるので注意。
                        if (listener?.onEncoderFormatChanged(encoder) == true) return
                        break
                    }
                    if (encoderOutputBufferIndex < 0) break

                    val encoderOutputBuffer = requireNotNull(encoder.getOutputBuffer(encoderOutputBufferIndex))
                    // NOTE: flags が MediaCodec.BUFFER_FLAG_CODEC_CONFIG かつ BUFFER_FLAG_END_OF_STREAM
                    // のときがあるので注意。
                    if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            && encoderOutputBufferInfo.size != 0) {
                        if (lastEncoderOutputPts >= encoderOutputBufferInfo.presentationTimeUs) {
                            Timber.tag(TAG).w("encoder output pts(%d) is smaller than last pts(%d)",
                                    encoderOutputBufferInfo.presentationTimeUs, lastEncoderOutputPts)
                        } else {
                            if (newTrackIndex != null) {
                                muxer.writeSampleData(newTrackIndex, encoderOutputBuffer,
                                        encoderOutputBufferInfo)
                                lastEncoderOutputPts = encoderOutputBufferInfo.presentationTimeUs
                                // 進捗更新
                                if ((System.nanoTime() - startTimeNs) / 1000 / 1000 > UPDATE_PROGRESS_INTERVAL_MS
                                        * (deliverProgressCount + 1)) {
                                    deliverProgressCount++
                                    onProgressListener?.onProgress((encoderOutputBufferInfo.presentationTimeUs * 100 / durationUs).toInt())
                                }
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                    if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Timber.tag(TAG).d("audio encoder: EOS")
                        return
                    }
                    break
                }
            }
        } catch (e: DecodeException) { // recoverable error
            Timber.tag(TAG).e(e, "Recoverable error occurred on audio shrink.")
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Unrecoverable error occurred on audio shrink.")
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
        val codec = Utils.selectCodec(mimeType, false)
        if (codec == null) {
            val detailMessage = "audio decoder codec is not found. mime-type:$mimeType"
            Timber.tag(TAG).e(detailMessage)
            throw DecoderCreationException(detailMessage)
        }
        val codecName = codec.name
        return try {
            // 古いAndroidではNullableだったのでそのまま残している
            @Suppress("RedundantNullableReturnType")
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
        val codec = Utils.selectCodec(ENCODE_MIMETYPE, true)
        if (codec == null) {
            val detailMessage = "audio encoder codec is not found. media-type:$ENCODE_MIMETYPE"
            Timber.tag(TAG).e(detailMessage)
            throw EncoderCreationException(detailMessage)
        }
        val codecName = codec.name
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
        private const val ENCODE_MIMETYPE = "audio/mp4a-latm"

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