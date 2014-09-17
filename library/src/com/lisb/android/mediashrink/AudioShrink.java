package com.lisb.android.mediashrink;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

public class AudioShrink {

	private static final String LOG_TAG = AudioShrink.class.getSimpleName();
	private static final boolean VERBOSE = false;

	private static final long TIMEOUT_USEC = 250;
	private static final String CODEC = "audio/mp4a-latm";
	private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectHE;
	private int bitRate;

	private final MediaExtractor extractor;
	private final MediaMuxer muxer;

	private int sampleCount;

	private OnProgressListener onProgressListener;

	private static final long UPDATE_PROGRESS_INTERVAL_MS = 3 * 1000;

	public AudioShrink(MediaExtractor extractor, MediaMuxer muxer) {
		this.extractor = extractor;
		this.muxer = muxer;
	}

	public void setBitRate(int bitRate) {
		this.bitRate = bitRate;
	}

	public void setOnProgressListener(OnProgressListener onProgressListener) {
		this.onProgressListener = onProgressListener;
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

	private void reencode(final int trackIndex, final Integer newTrackIndex,
			final ReencodeListener listener) throws DecodeException {
		final MediaFormat currentFormat = extractor.getTrackFormat(trackIndex);
		final MediaFormat encoderConfigurationFormat = createEncoderConfigurationFormat(currentFormat);

		MediaCodec encoder = null;
		MediaCodec decoder = null;

		ByteBuffer[] decoderInputBuffers = null;
		ByteBuffer[] decoderOutputBuffers = null;
		MediaCodec.BufferInfo decoderOutputBufferInfo = null;

		ByteBuffer[] encoderInputBuffers = null;
		ByteBuffer[] encoderOutputBuffers = null;
		MediaCodec.BufferInfo encoderOutputBufferInfo = null;

		// 進捗取得に利用
		final float durationUs = currentFormat
				.getLong(MediaFormat.KEY_DURATION);
		final long startTimeNs = System.nanoTime();
		long deliverProgressCount = 0;

		try {
			encoder = createEncoder(encoderConfigurationFormat);
			encoder.start();

			encoderInputBuffers = encoder.getInputBuffers();
			encoderOutputBuffers = encoder.getOutputBuffers();
			encoderOutputBufferInfo = new MediaCodec.BufferInfo();

			decoder = createDecoder(currentFormat);

			if (decoder == null) {
				Log.e(LOG_TAG, "audio decoder not found.");
				throw new DecodeException("audio decoder not found.");
			}

			decoder.start();

			decoderInputBuffers = decoder.getInputBuffers();
			decoderOutputBuffers = decoder.getOutputBuffers();
			decoderOutputBufferInfo = new MediaCodec.BufferInfo();

			extractor.selectTrack(trackIndex);

			boolean extractorDone = false;
			boolean decoderDone = false;

			int pendingDecoderOutputBufferIndex = -1;

			long lastExtracterOutputPts = -1;
			long lastDecoderOutputPts = -1;
			long lastEncoderOutputPts = -1;

			while (true) {
				while (!extractorDone) {
					final int decoderInputBufferIndex = decoder
							.dequeueInputBuffer(TIMEOUT_USEC);
					if (decoderInputBufferIndex < 0) {
						break;
					}
					final ByteBuffer decoderInputBuffer = decoderInputBuffers[decoderInputBufferIndex];
					final int size = extractor.readSampleData(
							decoderInputBuffer, 0);

					final long pts = extractor.getSampleTime();
					if (VERBOSE) {
						Log.v(LOG_TAG, "audio extractor output. size:" + size
								+ ", sample time:" + pts + ", sample flags:"
								+ extractor.getSampleFlags());
					}

					if (size >= 0) {
						if (lastExtracterOutputPts >= pts) {
							Log.w(LOG_TAG, "extractor output pts(" + pts
									+ ") is smaller than last pts("
									+ +lastExtracterOutputPts + ").");
						} else {
							lastExtracterOutputPts = pts;
						}

						decoder.queueInputBuffer(decoderInputBufferIndex, 0,
								size, pts, extractor.getSampleFlags());
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

					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						decoder.releaseOutputBuffer(decoderOutputBufferIndex,
								false);
						break;
					}
					pendingDecoderOutputBufferIndex = decoderOutputBufferIndex;

					final long pts = decoderOutputBufferInfo.presentationTimeUs;
					if (VERBOSE) {
						Log.v(LOG_TAG, "audio decoder output. time:" + pts
								+ ", offset:" + decoderOutputBufferInfo.offset
								+ ", size:" + decoderOutputBufferInfo.size
								+ ", flag:" + decoderOutputBufferInfo.flags);
					}

					if (lastDecoderOutputPts >= pts) {
						Log.w(LOG_TAG, "decoder output pts(" + pts
								+ ") is smaller than last pts("
								+ lastDecoderOutputPts + ").");
					} else {
						lastDecoderOutputPts = pts;
					}

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

					if (decoderOutputBufferInfo.size != 0 && listener != null) {
						if (listener.onFrameDecoded(decoder)) {
							return;
						}
					}

					pendingDecoderOutputBufferIndex = -1;
					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "audio decoder: EOS");
						decoderDone = true;
					}
					break;
				}

				while (true) {
					final int encoderOutputBufferIndex = encoder
							.dequeueOutputBuffer(encoderOutputBufferInfo,
									TIMEOUT_USEC);

					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						Log.d(LOG_TAG, "audio encoder: output buffers changed");
						encoderOutputBuffers = encoder.getOutputBuffers();
						break;
					}

					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						// エンコーダに何か入力しないと、ここに来ないエンコーダがあるので注意。
						if (listener != null) {
							if (listener.onEncoderFormatChanged(encoder)) {
								return;
							}
						}
						break;
					}

					if (encoderOutputBufferIndex < 0) {
						break;
					}

					final ByteBuffer encoderOutputBuffer = encoderOutputBuffers[encoderOutputBufferIndex];
					if ((encoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						encoder.releaseOutputBuffer(encoderOutputBufferIndex,
								false);
						break;
					}

					final long pts = encoderOutputBufferInfo.presentationTimeUs;
					if (VERBOSE) {
						Log.v(LOG_TAG, "audio encoder output. time:" + pts
								+ ", offset:" + encoderOutputBufferInfo.offset
								+ ", size:" + encoderOutputBufferInfo.size
								+ ", flag:" + encoderOutputBufferInfo.flags);
					}

					if (encoderOutputBufferInfo.size != 0) {
						if (lastEncoderOutputPts >= pts) {
							Log.w(LOG_TAG, "encoder output pts(" + pts
									+ ") is smaller than last pts("
									+ lastEncoderOutputPts + ").");
						} else {
							if (newTrackIndex != null) {
								muxer.writeSampleData(newTrackIndex,
										encoderOutputBuffer,
										encoderOutputBufferInfo);
								lastEncoderOutputPts = pts;

								// 進捗更新
								if ((System.nanoTime() - startTimeNs) / 1000 / 1000 > UPDATE_PROGRESS_INTERVAL_MS
										* (deliverProgressCount + 1)) {
									deliverProgressCount++;
									if (onProgressListener != null) {
										onProgressListener
												.onProgress((int) (encoderOutputBufferInfo.presentationTimeUs * 100 / durationUs));
									}
								}
							}
						}
					}
					if ((encoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "audio encoder: EOS");
						return;
					}
					encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
					break;
				}
			}
		} finally {
			if (encoder != null) {
				encoder.stop();
				encoder.release();
			}

			if (decoder != null) {
				decoder.stop();
				decoder.release();
			}

			extractor.unselectTrack(trackIndex);
		}
	}

	public MediaFormat createOutputFormat(final int trackIndex)
			throws DecodeException {
		sampleCount = 0;
		final AtomicReference<MediaFormat> formatRef = new AtomicReference<>();
		reencode(trackIndex, null, new ReencodeListener() {
			@Override
			public boolean onFrameDecoded(MediaCodec decoder) {
				sampleCount++;
				return false;
			}

			@Override
			public boolean onEncoderFormatChanged(MediaCodec encoder) {
				Log.d(LOG_TAG, "audio encoder: output format changed. "
						+ encoder.getOutputFormat());
				formatRef.set(encoder.getOutputFormat());
				return false;
			}
		});

		if (sampleCount == 0) {
			Log.e(LOG_TAG, "no audio sample found.");
			throw new DecodeException("no audio sample found.");
		}

		return formatRef.get();
	}

	public void shrink(final int trackIndex, final int newTrackIndex)
			throws DecodeException {
		reencode(trackIndex, newTrackIndex, null);
	}

	private MediaCodec createDecoder(final MediaFormat format) {
		final String mimeType = format.getString(MediaFormat.KEY_MIME);
		final MediaCodec decoder = MediaCodec.createByCodecName(Utils
				.selectCodec(mimeType, false).getName());
		if (decoder != null) {
			decoder.configure(format, null, null, 0);

			Log.d(LOG_TAG, "audio decoder:" + decoder.getName());
		}
		return decoder;
	}

	private MediaCodec createEncoder(final MediaFormat format) {
		final MediaCodec encoder = MediaCodec.createByCodecName(Utils
				.selectCodec(CODEC, true).getName());
		encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		Log.d(LOG_TAG, "audio encoder:" + encoder.getName());
		return encoder;
	}

	private interface ReencodeListener {
		/**
		 * @return if stop or not
		 */
		boolean onFrameDecoded(MediaCodec decoder);

		/**
		 * @return if stop or not
		 */
		boolean onEncoderFormatChanged(MediaCodec encoder);
	}

}
