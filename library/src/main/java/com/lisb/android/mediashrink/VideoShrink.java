package com.lisb.android.mediashrink;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class VideoShrink {

	private static final String TAG = "VideoShrink";
	private static final boolean VERBOSE = false;
	private static final boolean DEBUG = false; // デバッグ用にスナップショットを出力する

	private static final String CODEC = "video/avc";
	private static final long TIMEOUT_USEC = 250;
	private static final int I_FRAME_INTERVAL = 5;

	// フレームレートはビットレート/フレームレートでフレーム一枚あたりのビット数を割り出すために存在する。
	// そのため厳密に実際の動画のレートに合わせる必要はない。
	// ただし実際のフレーム数より少なく設定してしまうとファイル数が想定より大きくなってしまうので、
	// MAX_FRAME_INTERVAL_MSEC をこえた場合、間引く。
	private static final int FRAMERATE = 30;
	private static final float MAX_FRAME_INTERVAL_MS = 1000f / (FRAMERATE * 1.1f);

	private static final String SNAPSHOT_FILE_PREFIX = "android-videoshrink-snapshot";
	private static final String SNAPSHOT_FILE_EXTENSION = "jpg";
	private static final int NUMBER_OF_SNAPSHOT = 10;

	private final MediaExtractor extractor;
	private final MediaMetadataRetriever metadataRetriever;
	private final MediaMuxer muxer;
	private final UnrecoverableErrorCallback errorCallback;
	private final int rotation;

	private int bitRate;
	private int width = -1;

	private OnProgressListener onProgressListener;

	private static final long UPDATE_PROGRESS_INTERVAL_MS = 3 * 1000;

	public VideoShrink(final MediaExtractor extractor,
			final MediaMetadataRetriever retriever, final MediaMuxer muxer,
			final UnrecoverableErrorCallback errorCallback) {
		this.extractor = extractor;
		this.metadataRetriever = retriever;
		this.muxer = muxer;
		this.errorCallback = errorCallback;

        this.rotation = Integer
                .parseInt(metadataRetriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
	}

	/**
	 * 指定必須
	 * 
	 * Warning: Nexus 7 では決まった幅(640, 384など)でないとエンコード結果がおかしくなる。
	 * セットした値で正しくエンコードできるかテストすること。
	 */
	public void setWidth(int width) {
		if (width > 0 && width % 16 > 0) {
			throw new IllegalArgumentException(
					"Only multiples of 16 is supported.");
		}
		this.width = width;
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
	private MediaFormat createEncoderConfigurationFormat(MediaFormat origin)
			throws DecodeException {
		final int originWidth;
		final int originHeight;

		if (rotation == 90 || rotation == 270) {
			originWidth = origin.getInteger(MediaFormat.KEY_HEIGHT);
			originHeight = origin.getInteger(MediaFormat.KEY_WIDTH);
		} else {
			originWidth = origin.getInteger(MediaFormat.KEY_WIDTH);
			originHeight = origin.getInteger(MediaFormat.KEY_HEIGHT);
		}



		final float widthRatio = (float) width / originWidth;
		// アスペクト比を保ったまま、16の倍数になるように(エンコードの制限) width, height を指定する。
		final int height = getMultipliesOf16(originHeight * widthRatio);

		final MediaFormat format = MediaFormat.createVideoFormat(CODEC, width,
				height);
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

		// TODO ビットレートが元の値より大きくならないようにする
		format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
		format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAMERATE);

		Log.d(TAG, "create encoder configuration format:" + format + ", rotation:" + rotation);

		return format;
	}

	/**
	 * 指定された数字に最も近い16の倍数の値を返す
	 */
	private int getMultipliesOf16(float number) {
		final int round = Math.round(number);
		final int rem = round % 16;
		if (rem < 8) {
			return round - rem;
		} else {
			return round + 16 - rem;
		}
	}

	private interface ReencodeListener {
		/**
		 * @return if stop or not
		 */
		boolean onEncoderFormatChanged(MediaCodec encoder);
	}

	@SuppressWarnings("unused")
	private void reencode(final int trackIndex, final Integer newTrackIndex,
			final ReencodeListener listener) throws DecodeException {
		final MediaFormat currentFormat = extractor.getTrackFormat(trackIndex);

		MediaCodec encoder = null;
		MediaCodec decoder = null;

		OutputSurface outputSurface = null;
		InputSurface inputSurface = null;

		ByteBuffer[] decoderInputBuffers = null;
		ByteBuffer[] encoderOutputBuffers = null;
		MediaCodec.BufferInfo decoderOutputBufferInfo = null;
		MediaCodec.BufferInfo encoderOutputBufferInfo = null;

		boolean extractorDone = false;
		boolean decoderDone = false;

		SnapshotOptions snapshotOptions = null;
		long snapshotDuration = 0;
		int snapshotIndex = 0;

		// 進捗取得に利用
		final float durationUs = currentFormat
				.getLong(MediaFormat.KEY_DURATION);
		final long startTimeNs = System.nanoTime();
		long deliverProgressCount = 0;

		try {
			final MediaFormat encoderConfigurationFormat = createEncoderConfigurationFormat(currentFormat);
			encoder = createEncoder(encoderConfigurationFormat);
			inputSurface = new InputSurface(encoder.createInputSurface());
			inputSurface.makeCurrent();
			encoder.start();

			encoderOutputBufferInfo = new MediaCodec.BufferInfo();
			encoderOutputBuffers = encoder.getOutputBuffers();

			if (DEBUG) {
				snapshotOptions = new SnapshotOptions();
				snapshotOptions.width = encoderConfigurationFormat
						.getInteger(MediaFormat.KEY_WIDTH);
				snapshotOptions.height = encoderConfigurationFormat
						.getInteger(MediaFormat.KEY_HEIGHT);
				snapshotDuration = currentFormat
						.getLong(MediaFormat.KEY_DURATION) / NUMBER_OF_SNAPSHOT;
			} else {
				snapshotOptions = null;
				snapshotDuration = 0;
			}

            // lollipop から Surface への出力時に自動で回転して出力するようになったため、こちら側では回転を行わない。
            // https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
			outputSurface = new OutputSurface(Build.VERSION.SDK_INT >= 21 ? 0 : -rotation);
			decoder = createDecoder(currentFormat, outputSurface.getSurface());
			if (decoder == null) {
				Log.e(TAG, "video decoder not found.");
				throw new DecodeException("video decoder not found.");
			}

			decoder.start();

			decoderInputBuffers = decoder.getInputBuffers();
			decoderOutputBufferInfo = new MediaCodec.BufferInfo();

			extractor.selectTrack(trackIndex);

			int frameCount = 0;
			long lastDecodePresentationTimeMs = 0;
			while (true) {
				while (!extractorDone) {
					final int decoderInputBufferIndex = decoder
							.dequeueInputBuffer(TIMEOUT_USEC);
					if (decoderInputBufferIndex < 0) {
						break;
					}
					final ByteBuffer decoderInputBuffer = decoderInputBuffers[decoderInputBufferIndex];
					final int size = extractor.readSampleData(decoderInputBuffer, 0);
					// extractor.advance() より先に行うこと
					final long sampleTime = extractor.getSampleTime();
					int sampleFlags = extractor.getSampleFlags();

					if (VERBOSE) {
						Log.v(TAG,
								"video extractor output. size:" + size
										+ ", sample time:"
										+ sampleTime
										+ ", sample flags:"
										+ sampleFlags);
					}

					extractorDone = !extractor.advance();
					if (extractorDone) {
						Log.d(TAG, "video extractor: EOS, size:" + size);
						sampleFlags |=  MediaCodec.BUFFER_FLAG_END_OF_STREAM;
					}

					if (size >= 0) {
						decoder.queueInputBuffer(decoderInputBufferIndex, 0, size, sampleTime,
								sampleFlags);
					} else if (extractorDone) {
						decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0, sampleFlags);
					}
					break;
				}

				while (!decoderDone) {
					int decoderOutputBufferIndex = decoder.dequeueOutputBuffer(
							decoderOutputBufferInfo, TIMEOUT_USEC);

					if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						Log.d(TAG, "video decoder: output format changed. "
								+ Utils.toString(decoder.getOutputFormat()));
					}

					if (decoderOutputBufferIndex < 0) {
						break;
					}

					if (VERBOSE) {
						Log.v(TAG, "video decoder output. time:"
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

					final boolean render = decoderOutputBufferInfo.size != 0;
					decoder.releaseOutputBuffer(decoderOutputBufferIndex,
							render);

					if (decoderOutputBufferInfo.size != 0) {
						frameCount++;
					}

					if (render) {
						if (DEBUG
								&& snapshotIndex * snapshotDuration <= decoderOutputBufferInfo.presentationTimeUs) {
							snapshotOptions.file = getSnapshotFile(
									snapshotIndex,
									decoderOutputBufferInfo.presentationTimeUs);
							outputSurface.drawNewImage(snapshotOptions);
							snapshotIndex++;
						} else {
							outputSurface.drawNewImage(null);
						}

						final long presentaionTimeMs = decoderOutputBufferInfo.presentationTimeUs / 1000;
						if (lastDecodePresentationTimeMs <= 0
								|| presentaionTimeMs
										- lastDecodePresentationTimeMs >= MAX_FRAME_INTERVAL_MS) {
							// lastDecodePresentaitonTimeMs
							// が0以下の場合は特殊なケースになりそうなので間引く対象から外す。
							inputSurface
									.setPresentationTime(decoderOutputBufferInfo.presentationTimeUs * 1000);
							inputSurface.swapBuffers();
							lastDecodePresentationTimeMs = presentaionTimeMs;
						} else {
							Log.i(TAG,
									"frame removed because frame interval is too short. current:"
											+ presentaionTimeMs + ", last:"
											+ lastDecodePresentationTimeMs);
						}
					}

					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						if (frameCount == 0) {
							Log.e(TAG, "no video frame found.");
							throw new DecodeException("no video frame found.");
						}

						Log.d(TAG, "video decoder: EOS");
						decoderDone = true;
						encoder.signalEndOfInputStream();
					}

					break;
				}

				while (true) {
					final int encoderOutputBufferIndex = encoder
							.dequeueOutputBuffer(encoderOutputBufferInfo,
									TIMEOUT_USEC);
					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						Log.d(TAG, "video encoder: output buffers changed");
						encoderOutputBuffers = encoder.getOutputBuffers();
						break;
					}
					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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

					if (VERBOSE) {
						Log.v(TAG, "video encoder output. time:"
								+ encoderOutputBufferInfo.presentationTimeUs
								+ ", offset:" + encoderOutputBufferInfo.offset
								+ ", size:" + encoderOutputBufferInfo.size
								+ ", flag:" + encoderOutputBufferInfo.flags);
					}

					if ((encoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						// エンコーダに何か入力しないと、ここに来ないエンコーダがあるので注意。
						encoder.releaseOutputBuffer(encoderOutputBufferIndex,
								false);
						break;
					}
					if (encoderOutputBufferInfo.size != 0) {
						if (newTrackIndex != null) {
							muxer.writeSampleData(newTrackIndex,
									encoderOutputBuffer,
									encoderOutputBufferInfo);

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
					if ((encoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(TAG, "video encoder: EOS");
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
			Log.e(TAG, "unrecoverable error occured on video shrink.", e);
			errorCallback.onUnrecoverableError(e);
		} finally {
			if (encoder != null) {
				encoder.stop();
				encoder.release();
			}

			if (inputSurface != null) {
				inputSurface.release();
			}

			if (decoder != null) {
				decoder.stop();
				decoder.release();
			}

			if (outputSurface != null) {
				outputSurface.release();
			}

			extractor.unselectTrack(trackIndex);
		}
	}

	private File getSnapshotFile(int snapshotIndex, long presentationTimeUs) {
		return new File(Environment.getExternalStorageDirectory(),
				SNAPSHOT_FILE_PREFIX + snapshotIndex + "_" + presentationTimeUs
						/ 1000 + "." + SNAPSHOT_FILE_EXTENSION);
	}

	public MediaFormat createOutputFormat(final int trackIndex)
			throws DecodeException {
		final AtomicReference<MediaFormat> formatRef = new AtomicReference<>();
		reencode(trackIndex, null, new ReencodeListener() {
			@Override
			public boolean onEncoderFormatChanged(MediaCodec encoder) {
				Log.d(TAG,
						"video encoder: output format changed. "
								+ Utils.toString(encoder.getOutputFormat()));
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

	private MediaCodec createDecoder(final MediaFormat format,
			final Surface surface) throws DecoderCreationException {
		final String codecName = Utils
				.selectCodec(format.getString(MediaFormat.KEY_MIME), false)
				.getName();
		try {
			final MediaCodec decoder = MediaCodec.createByCodecName(codecName);
			if (decoder != null) {
				decoder.configure(format, surface, null, 0);

				Log.d(TAG, "video decoder:" + decoder.getName());
			}
			return decoder;
		} catch (IOException e) {
			// later Lollipop.
			final String detailMessage = "video decoder cannot be created. codec-name:" + codecName;
			Log.e(TAG, detailMessage, e);
			throw new DecoderCreationException(detailMessage, e);
		} catch (IllegalStateException e) {
			final String detailMessage = "video decoder cannot be created. codec-name:" + codecName;
			Log.e(TAG, detailMessage, e);
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

			Log.d(TAG, "video encoder:" + encoder.getName());
			return encoder;
		} catch (IOException e) {
			// later Lollipop.
			final String detailMessage = "video encoder cannot be created. codec-name:" + codecName;
			Log.e(TAG, detailMessage, e);
			throw new EncoderCreationException(detailMessage, e);
		} catch (IllegalStateException e) {
			// TODO Change Detail Message If minSDKVersion > 21
			final String detailMessage = "video encoder cannot be created. codec-name:" + codecName;
			Log.e(TAG, detailMessage, e);
			throw new EncoderCreationException(detailMessage, e);
		}
	}
}
