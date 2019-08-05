package com.lisb.android.mediashrink;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class AudioShrink {

	private static final String TAG = "AudioShrink";

	private static final long TIMEOUT_USEC = 250;
	private static final String CODEC = "audio/mp4a-latm";
	// Because AACObjectHE of some encoder(ex. OMX.google.aac.encoder) is buggy,
	// Don't use AACObjectHE.
	//
	// bug example:
	// - specify mono sound but output stereo sound.
	// - output wrong time_base_codec.
	private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
	private int bitRate;

	private final MediaExtractor extractor;
	private final MediaMuxer muxer;
	private final UnrecoverableErrorCallback errorCallback;

	private OnProgressListener onProgressListener;

	private static final long UPDATE_PROGRESS_INTERVAL_MS = 3 * 1000;

	public AudioShrink(MediaExtractor extractor, MediaMuxer muxer,
			UnrecoverableErrorCallback errorCallback) {
		this.extractor = extractor;
		this.muxer = muxer;
		this.errorCallback = errorCallback;
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
		final int channelCount = origin
				.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
		final MediaFormat format = MediaFormat.createAudioFormat(CODEC,
				origin.getInteger(MediaFormat.KEY_SAMPLE_RATE), channelCount);
		// TODO ビットレートが元の値より大きくならないようにする
		format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
		format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);

		Timber.tag(TAG).d("create audio encoder configuration format:%s", Utils.toString(format));

		return format;
	}

	private void reencode(final int trackIndex, final Integer newTrackIndex,
			final ReencodeListener listener) throws DecodeException {
		final MediaFormat originalFormat = extractor.getTrackFormat(trackIndex);
		Timber.tag(TAG).d("original format:%s", Utils.toString(originalFormat));
		final MediaFormat encoderConfigurationFormat = createEncoderConfigurationFormat(originalFormat);

		MediaCodec encoder = null;
		MediaCodec decoder = null;

		ByteBuffer[] decoderInputBuffers = null;
		ByteBuffer[] decoderOutputBuffers = null;
		MediaCodec.BufferInfo decoderOutputBufferInfo = null;

		ByteBuffer[] encoderInputBuffers = null;
		ByteBuffer[] encoderOutputBuffers = null;
		MediaCodec.BufferInfo encoderOutputBufferInfo = null;

		// 進捗取得に利用
		final float durationUs = originalFormat
				.getLong(MediaFormat.KEY_DURATION);
		final long startTimeNs = System.nanoTime();
		long deliverProgressCount = 0;

		try {
			// create encoder
			encoder = createEncoder(encoderConfigurationFormat);
			encoder.start();

			encoderInputBuffers = encoder.getInputBuffers();
			encoderOutputBuffers = encoder.getOutputBuffers();
			encoderOutputBufferInfo = new MediaCodec.BufferInfo();

			// create decorder
			decoder = createDecoder(originalFormat);
			if (decoder == null) {
				Timber.tag(TAG).e("audio decoder not found.");
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

			int sampleCount = 0;
			while (true) {
				// read from extractor, write to decoder
				while (!extractorDone) {
					final int decoderInputBufferIndex = decoder
							.dequeueInputBuffer(TIMEOUT_USEC);
					if (decoderInputBufferIndex < 0) {
						break;
					}
					final ByteBuffer decoderInputBuffer = decoderInputBuffers[decoderInputBufferIndex];
					final int size = extractor.readSampleData(decoderInputBuffer, 0);
					// extractor.advance() より先に行うこと
					final long pts = extractor.getSampleTime();
					int sampleFlags = extractor.getSampleFlags();

					Timber.tag(TAG).v("audio extractor output. size:%d, sample time:%d, sample flags:%d", size,
							pts, sampleFlags);

					extractorDone = !extractor.advance();
					if (extractorDone) {
						Timber.tag(TAG).d("audio extractor: EOS, size:%d, sampleCount:%d",
								size, sampleCount);
						sampleFlags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
						if (sampleCount == 0) {
							Timber.tag(TAG).e("no audio sample found.");
							throw new DecodeException("no audio sample found.");
						}
					}

					if (size >= 0) {
						if (lastExtracterOutputPts >= pts) {
							Timber.tag(TAG).w("extractor output pts(%d) is smaller than last pts(%d)",
									pts, lastExtracterOutputPts);
						} else {
							lastExtracterOutputPts = pts;
						}

						decoder.queueInputBuffer(decoderInputBufferIndex, 0, size, pts, sampleFlags);
					} else if (extractorDone) {
						decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0, sampleFlags);
					}

					break;
				}

				// read from decoder
				while (!decoderDone && pendingDecoderOutputBufferIndex == -1) {
					final int decoderOutputBufferIndex = decoder
							.dequeueOutputBuffer(decoderOutputBufferInfo,
									TIMEOUT_USEC);
					if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						Timber.tag(TAG).d("audio decoder: output buffers changed");
						decoderOutputBuffers = decoder.getOutputBuffers();
						break;
					}
					if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						Timber.tag(TAG).d("audio decoder: output format changed. %s",
								Utils.toString(decoder.getOutputFormat()));
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
					Timber.tag(TAG).v("audio decoder output. time:%d, offset:%d, size:%d, flags:%d",
							pts, decoderOutputBufferInfo.offset, decoderOutputBufferInfo.size,
							decoderOutputBufferInfo.flags);

					if (lastDecoderOutputPts >= pts) {
						Timber.tag(TAG).w("decoder output pts(%d) is smaller than last pts(%d)",
								pts, lastDecoderOutputPts);
					} else {
						lastDecoderOutputPts = pts;
					}

					break;
				}

				// write to encoder
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

					if (decoderOutputBufferInfo.size != 0) {
						sampleCount++;
					}

					pendingDecoderOutputBufferIndex = -1;
					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Timber.tag(TAG).d("audio decoder: EOS");
						decoderDone = true;
					}
					break;
				}

				// write to muxer
				while (true) {
					final int encoderOutputBufferIndex = encoder
							.dequeueOutputBuffer(encoderOutputBufferInfo,
									TIMEOUT_USEC);

					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						Timber.tag(TAG).d("audio encoder: output buffers changed");
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
					Timber.tag(TAG).v("audio encoder output. time:%d, offset:%d, size:%d, flags:%d",
							pts, encoderOutputBufferInfo.offset, encoderOutputBufferInfo.size,
							encoderOutputBufferInfo.flags);

					if (encoderOutputBufferInfo.size != 0) {
						if (lastEncoderOutputPts >= pts) {
							Timber.tag(TAG).w("encoder output pts(%d) is smaller than last pts(%d)",
									pts, lastEncoderOutputPts);
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
						Timber.tag(TAG).d("audio encoder: EOS");
						return;
					}
					encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
					break;
				}
			}
		} catch (DecodeException e) {
			// recoverable error
			throw e;
		} catch (Throwable e) {
			Timber.tag(TAG).e(e, "unrecoverable error occured on audio shrink.");
			errorCallback.onUnrecoverableError(e);
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
		final AtomicReference<MediaFormat> formatRef = new AtomicReference<>();
		reencode(trackIndex, null, new ReencodeListener() {
			@Override
			public boolean onEncoderFormatChanged(MediaCodec encoder) {
                Timber.tag(TAG).d("audio encoder: output format changed. %s",
                        Utils.toString(encoder.getOutputFormat()));
				formatRef.set(encoder.getOutputFormat());
				return true;
			}
		});
		return formatRef.get();
	}

	public void shrink(final int trackIndex, final int newTrackIndex)
			throws DecodeException {
		reencode(trackIndex, newTrackIndex, null);
	}

	private MediaCodec createDecoder(final MediaFormat format)
			throws DecoderCreationException {
		final String mimeType = format.getString(MediaFormat.KEY_MIME);
		final String codecName = Utils.selectCodec(mimeType, false).getName();
		try {
			final MediaCodec decoder = MediaCodec.createByCodecName(codecName);
			if (decoder != null) {
				decoder.configure(format, null, null, 0);

				Timber.tag(TAG).d("audio decoder:%s", decoder.getName());
			}
			return decoder;
		} catch (IOException e) {
			// later Lollipop.
			final String detailMessage = "audio decoder cannot be created. codec-name:" + codecName;
			Timber.tag(TAG).e(e, detailMessage);
			throw new DecoderCreationException(detailMessage, e);
		} catch (IllegalStateException e) {
			final String detailMessage = "audio decoder cannot be created. codec-name:" + codecName;
			Timber.tag(TAG).e(e, detailMessage);
			throw new DecoderCreationException(detailMessage, e);
		}
	}

	private MediaCodec createEncoder(final MediaFormat format)
			throws EncoderCreationException {
		final String codecName = Utils.selectCodec(CODEC, true).getName();
		try {
			final MediaCodec encoder = MediaCodec.createByCodecName(codecName);
			encoder.configure(format, null, null,
					MediaCodec.CONFIGURE_FLAG_ENCODE);
			Timber.tag(TAG).d("audio encoder:%s", encoder.getName());
			return encoder;
		} catch (IOException e) {
			// later Lollipop.
			final String detailMessage = "audio encoder cannot be created. codec-name:" + codecName;
			Timber.tag(TAG).e(e, detailMessage);
			throw new EncoderCreationException(detailMessage, e);
		} catch (IllegalStateException e) {
			// TODO Change Detail Message If minSDKVersion > 21
			final String detailMessage = "audio encoder cannot be created. codec-name:" + codecName;
			Timber.tag(TAG).e(e, detailMessage);
			throw new EncoderCreationException(detailMessage, e);
		}
	}

	private interface ReencodeListener {
		/**
		 * @return if stop or not
		 */
		boolean onEncoderFormatChanged(MediaCodec encoder);
	}

}
