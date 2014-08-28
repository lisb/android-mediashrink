package com.lisb.android.mediashrink;

import java.nio.ByteBuffer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

public class AudioShrink {

	private static final String LOG_TAG = AudioShrink.class.getSimpleName();
	private static final boolean VERBOSE = true;

	private static final long TIMEOUT_USEC = 250;
	private static final String CODEC = "audio/mp4a-latm";
	private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectHE;
	private int bitRate;

	private final MediaExtractor extractor;
	private final MediaMuxer muxer;

	public AudioShrink(MediaExtractor extractor, MediaMuxer muxer) {
		this.extractor = extractor;
		this.muxer = muxer;
	}

	public void setBitRate(int bitRate) {
		this.bitRate = bitRate;
	}

	public boolean isSupportFormat(int trackIndex) {
		// TODO
		return true;
	}

	/**
	 * エンコーダの設定に使うフォーマットを作成する。 <br/>
	 * {@link MediaMuxer#addTrack(MediaFormat)} には利用できない。
	 */
	private MediaFormat createEncoderConfigurationFormat(MediaFormat origin) {
		final MediaFormat format = MediaFormat.createAudioFormat(CODEC,
				origin.getInteger(MediaFormat.KEY_SAMPLE_RATE),
				origin.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
		// TODO ビットレートが元の値より大きくならないようにする
		format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
		format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);

		Log.d(LOG_TAG, "create audio encoder configuration format:" + format);

		return format;
	}

	private MediaFormat reencode(final int trackIndex, final boolean forFormat) {
		final MediaFormat currentFormat = extractor.getTrackFormat(trackIndex);
		final MediaFormat encoderConfigurationFormat = createEncoderConfigurationFormat(currentFormat);

		final MediaCodec encoder = createEncoder(encoderConfigurationFormat);
		encoder.start();

		final MediaCodec decoder = createDecoder(currentFormat);
		decoder.start();

		try {
			extractor.selectTrack(trackIndex);

			final ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
			ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();
			final MediaCodec.BufferInfo decoderOutputBufferInfo = new MediaCodec.BufferInfo();

			final ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
			ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
			final MediaCodec.BufferInfo encoderOutputBufferInfo = new MediaCodec.BufferInfo();

			MediaFormat outputFormat = null;
			boolean extractorDone = false;
			boolean decoderDone = false;
			boolean encoderDone = false;

			int pendingDecoderOutputBufferIndex = -1;

			while (!encoderDone) {
				while (!extractorDone) {
					final int decoderInputBufferIndex = decoder
							.dequeueInputBuffer(TIMEOUT_USEC);
					if (decoderInputBufferIndex < 0) {
						break;
					}
					final ByteBuffer decoderInputBuffer = decoderInputBuffers[decoderInputBufferIndex];
					final int size = extractor.readSampleData(
							decoderInputBuffer, 0);

					if (VERBOSE) {
						Log.v(LOG_TAG,
								"audio extractor output. size:" + size
										+ ", sample time:"
										+ extractor.getSampleTime()
										+ ", sample flags:"
										+ extractor.getSampleFlags());
					}

					if (size >= 0) {
						decoder.queueInputBuffer(decoderInputBufferIndex, 0,
								size, extractor.getSampleTime(),
								extractor.getSampleFlags());
					}
					extractorDone = !extractor.advance();
					if (extractorDone) {
						Log.d(LOG_TAG, "audio extractor: EOS");
						decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0,
								0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					}
					break;
				}

				while (!decoderDone && pendingDecoderOutputBufferIndex == -1) {
					final int decoderOutputBufferIndex = decoder
							.dequeueOutputBuffer(decoderOutputBufferInfo,
									TIMEOUT_USEC);
					if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						Log.d(LOG_TAG, "audio decoder: output buffers changed");
						decoderOutputBuffers = decoder.getOutputBuffers();
						break;
					}
					if (decoderOutputBufferIndex < 0) {
						break;
					}

					if (VERBOSE) {
						Log.v(LOG_TAG, "audio decoder output. time:"
								+ decoderOutputBufferInfo.presentationTimeUs
								+ ", offset:" + decoderOutputBufferInfo.offset
								+ ", size:" + decoderOutputBufferInfo.size
								+ ", flag:" + decoderOutputBufferInfo.flags);
					}

					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						decoder.releaseOutputBuffer(decoderOutputBufferIndex,
								false);
						break;
					}
					pendingDecoderOutputBufferIndex = decoderOutputBufferIndex;
					break;
				}

				while (pendingDecoderOutputBufferIndex != -1) {
					final int encoderInputBufferIndex = encoder
							.dequeueInputBuffer(TIMEOUT_USEC);
					if (encoderInputBufferIndex < 0) {
						break;
					}
					final ByteBuffer encoderInputBuffer = encoderInputBuffers[encoderInputBufferIndex];
					final ByteBuffer decoderOutputBuffer = decoderOutputBuffers[pendingDecoderOutputBufferIndex]
							.duplicate();

					decoderOutputBuffer
							.position(decoderOutputBufferInfo.offset);
					decoderOutputBuffer.limit(decoderOutputBufferInfo.offset
							+ decoderOutputBufferInfo.size);
					encoderInputBuffer.position(0);
					encoderInputBuffer.put(decoderOutputBuffer);

					encoder.queueInputBuffer(encoderInputBufferIndex, 0,
							decoderOutputBufferInfo.size,
							decoderOutputBufferInfo.presentationTimeUs,
							decoderOutputBufferInfo.flags);
					decoder.releaseOutputBuffer(
							pendingDecoderOutputBufferIndex, false);

					pendingDecoderOutputBufferIndex = -1;
					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "audio decoder: EOS");
						decoderDone = true;
					}
					break;
				}

				while (!encoderDone) {
					final int encoderOutputBufferIndex = encoder
							.dequeueOutputBuffer(encoderOutputBufferInfo,
									TIMEOUT_USEC);

					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						Log.d(LOG_TAG, "audio encoder: output buffers changed");
						encoderOutputBuffers = encoder.getOutputBuffers();
						break;
					}

					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						outputFormat = encoder.getOutputFormat();
						Log.d(LOG_TAG, "create new format:" + outputFormat);
						if (forFormat) {
							return outputFormat;
						}
						break;
					}

					if (encoderOutputBufferIndex < 0) {
						break;
					}

					if (VERBOSE) {
						Log.v(LOG_TAG, "audio encoder output. time:"
								+ encoderOutputBufferInfo.presentationTimeUs
								+ ", offset:" + encoderOutputBufferInfo.offset
								+ ", size:" + encoderOutputBufferInfo.size
								+ ", flag:" + encoderOutputBufferInfo.flags);
					}

					if (outputFormat == null) {
						Log.e(LOG_TAG, "Can't create new audio format.");
						throw new RuntimeException(
								"Can't create new audio format.");
					}

					final ByteBuffer encoderOutputBuffer = encoderOutputBuffers[encoderOutputBufferIndex];
					if ((encoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						encoder.releaseOutputBuffer(encoderOutputBufferIndex,
								false);
						break;
					}

					if (encoderOutputBufferInfo.size != 0) {
						muxer.writeSampleData(trackIndex, encoderOutputBuffer,
								encoderOutputBufferInfo);
					}
					if ((encoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "audio encoder: EOS");
						encoderDone = true;
					}
					encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
					break;
				}
			}
			return outputFormat;
		} finally {
			encoder.stop();
			encoder.release();
			decoder.stop();
			decoder.release();

			extractor.unselectTrack(trackIndex);
		}
	}

	public MediaFormat createOutputFormat(final int trackIndex) {
		return reencode(trackIndex, true);
	}

	public void shrink(final int trackIndex) {
		reencode(trackIndex, false);
	}

	private MediaCodec createDecoder(final MediaFormat format) {
		final MediaCodec decoder = MediaCodec.createDecoderByType(format
				.getString(MediaFormat.KEY_MIME));
		decoder.configure(format, null, null, 0);

		Log.d(LOG_TAG, "audio decoder:" + decoder.getName());
		return decoder;
	}

	private MediaCodec createEncoder(final MediaFormat format) {
		final MediaCodec encoder = MediaCodec.createByCodecName(Utils
				.selectCodec(CODEC, true).getName());
		encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		Log.d(LOG_TAG, "audio encoder:" + encoder.getName());
		return encoder;
	}

}
