package com.lisb.android.mediashrink;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

public class VideoShrink {

	private static final String LOG_TAG = VideoShrink.class.getSimpleName();
	private static final boolean VERBOSE = false;
	private static final boolean DEBUG = false; // デバッグ用にスナップショットを出力する

	private static final String CODEC = "video/avc";
	private static final long TIMEOUT_USEC = 250;
	private static final int I_FRAME_INTERVAL = 5;
	private static final float DEFAULT_FRAMERATE = 30f;
	private static final float MIN_FRAMERATE = DEFAULT_FRAMERATE / 3;

	private static final String SNAPSHOT_FILE_PREFIX = "android-videoshrink-snapshot";
	private static final String SNAPSHOT_FILE_EXTENSION = "jpg";
	private static final int NUMBER_OF_SNAPSHOT = 10;

	private final MediaExtractor extractor;
	private final MediaMetadataRetriever metadataRetriever;
	private final MediaMuxer muxer;
	private final int rotation;

	private int bitRate;
	private int maxWidth = -1;
	private int maxHeight = -1;

	private int frameCount;

	private OnProgressListener onProgressListener;

	private static final long UPDATE_PROGRESS_INTERVAL_MS = 3 * 1000;

	public VideoShrink(final MediaExtractor extractor,
			final MediaMetadataRetriever retriever, final MediaMuxer muxer) {
		this.extractor = extractor;
		this.metadataRetriever = retriever;
		this.muxer = muxer;

		this.rotation = Integer
				.parseInt(metadataRetriever
						.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
	}

	/**
	 * Warning: Nexus 7 では決まった幅(640, 384など)でないとエンコード結果がおかしくなる。
	 * セットした値で正しくエンコードできるかテストすること。
	 * 
	 * @param maxWidth
	 *            0以下の時、無視される。
	 */
	public void setMaxWidth(int maxWidth) {
		if (maxWidth > 0 && maxWidth % 16 > 0) {
			throw new IllegalArgumentException(
					"Only multiples of 16 is supported.");
		}
		this.maxWidth = maxWidth;
	}

	/*
	 * Nexus 7(2013) ではある幅以外だとエンコード結果がおかしくなるので 幅を固定して使うことになる。
	 * 幅を固定する以外のうまい方法が見つかるまではこのメソッドの使用不可にする。
	 * 
	 * @param maxHeight 0以下の時、無視される
	 */
	// public void setMaxHeight(int maxHeight) {
	// if (maxHeight > 0 && maxHeight % 16 > 0) {
	// throw new IllegalArgumentException(
	// "Only multiples of 16 is supported.");
	// }
	// this.maxHeight = maxHeight;
	// }

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

		// アスペクト比を保ったまま、16の倍数になるように(エンコードの制限) width, height を指定する。
		final int width;
		final int height;
		float widthRatio = 1;
		if (maxWidth > 0) {
			widthRatio = (float) maxWidth / originWidth;
		}
		float heightRatio = 1;
		if (maxHeight > 0) {
			heightRatio = (float) maxHeight / originHeight;
		}

		if (widthRatio == heightRatio) {
			width = maxWidth;
			height = maxHeight;
		} else if (widthRatio < heightRatio) {
			width = maxWidth;
			height = getMultipliesOf16(originHeight * widthRatio);
		} else {
			width = getMultipliesOf16(originWidth * heightRatio);
			height = maxHeight;
		}

		final MediaFormat format = MediaFormat.createVideoFormat(CODEC, width,
				height);
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

		// TODO ビットレートが元の値より大きくならないようにする
		format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);

		if (frameCount > 0) {
			final float durationSec = origin.getLong(MediaFormat.KEY_DURATION)
					/ (1000 * 1000);
			float frameRate = frameCount / durationSec;

			Log.d(LOG_TAG, "video frame-count:" + frameCount + ", frame-rate:"
					+ frameRate);
			if (frameRate < MIN_FRAMERATE) {
				Log.d(LOG_TAG, "frame rate is too small.");
				frameRate = MIN_FRAMERATE;
			}

			format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate);
		} else {
			format.setFloat(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAMERATE);
		}

		Log.d(LOG_TAG, "create encoder configuration format:" + format);

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
		boolean onFrameDecoded(MediaCodec decoder);

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

			outputSurface = new OutputSurface(-rotation);
			decoder = createDecoder(currentFormat, outputSurface.getSurface());
			if (decoder == null) {
				Log.e(LOG_TAG, "video decoder not found.");
				throw new DecodeException("video decoder not found.");
			}

			decoder.start();

			decoderInputBuffers = decoder.getInputBuffers();
			decoderOutputBufferInfo = new MediaCodec.BufferInfo();

			extractor.selectTrack(trackIndex);

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

					if (VERBOSE) {
						Log.v(LOG_TAG,
								"video extractor output. size:" + size
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
						Log.d(LOG_TAG, "video extractor: EOS");
						decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0,
								0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					}
					break;
				}

				while (!decoderDone) {
					int decoderOutputBufferIndex = decoder.dequeueOutputBuffer(
							decoderOutputBufferInfo, TIMEOUT_USEC);

					if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						Log.d(LOG_TAG, "video decoder: output format changed. "
								+ Utils.toString(decoder.getOutputFormat()));
					}

					if (decoderOutputBufferIndex < 0) {
						break;
					}

					if (VERBOSE) {
						Log.v(LOG_TAG, "video decoder output. time:"
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

					if (decoderOutputBufferInfo.size != 0 && listener != null) {
						if (listener.onFrameDecoded(decoder)) {
							return;
						}
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

						inputSurface
								.setPresentationTime(decoderOutputBufferInfo.presentationTimeUs * 1000);
						inputSurface.swapBuffers();
					}

					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "video decoder: EOS");
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
						Log.d(LOG_TAG, "video encoder: output buffers changed");
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
						Log.v(LOG_TAG, "video encoder output. time:"
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
						Log.d(LOG_TAG, "video encoder: EOS");
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
		frameCount = 0;
		final AtomicReference<MediaFormat> formatRef = new AtomicReference<>();
		reencode(trackIndex, null, new ReencodeListener() {
			@Override
			public boolean onFrameDecoded(MediaCodec decoder) {
				frameCount++;
				return false;
			}

			@Override
			public boolean onEncoderFormatChanged(MediaCodec encoder) {
				Log.d(LOG_TAG,
						"video encoder: output format changed. "
								+ Utils.toString(encoder.getOutputFormat()));
				formatRef.set(encoder.getOutputFormat());
				return false;
			}
		});

		if (frameCount == 0) {
			Log.e(LOG_TAG, "no video frame found.");
			throw new DecodeException("no video frame found.");
		}

		return formatRef.get();
	}

	public void shrink(final int trackIndex, final int newTrackIndex)
			throws DecodeException {
		reencode(trackIndex, newTrackIndex, null);
	}

	private MediaCodec createDecoder(final MediaFormat format,
			final Surface surface) {
		final MediaCodec decoder = MediaCodec.createByCodecName(Utils
				.selectCodec(format.getString(MediaFormat.KEY_MIME), false)
				.getName());
		if (decoder != null) {
			decoder.configure(format, surface, null, 0);

			Log.d(LOG_TAG, "video decoder:" + decoder.getName());
		}
		return decoder;
	}

	private MediaCodec createEncoder(final MediaFormat format) {
		final MediaCodec encoder = MediaCodec.createByCodecName(Utils
				.selectCodec(CODEC, true).getName());
		encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

		Log.d(LOG_TAG, "video encoder:" + encoder.getName());
		return encoder;
	}
}
