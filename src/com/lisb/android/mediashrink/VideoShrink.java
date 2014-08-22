package com.lisb.android.mediashrink;

import java.nio.ByteBuffer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

public class VideoShrink {

	private static final String LOG_TAG = VideoShrink.class.getSimpleName();
	private static final long TIMEOUT_USEC = 250;

	private final MediaExtractor extractor;
	private final MediaMetadataRetriever metadataRetriever;
	private final MediaMuxer muxer;
	private final int rotation;

	private int maxWidth = -1;
	private int maxHeight = -1;
	private long maxSize = 0;

	public VideoShrink(final MediaExtractor extractor,
			final MediaMetadataRetriever retriever, final MediaMuxer muxer) {
		this.extractor = extractor;
		this.metadataRetriever = retriever;
		this.muxer = muxer;

		this.rotation = Integer
				.parseInt(metadataRetriever
						.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
	}

	public void setMaxWidth(int maxWidth) {
		this.maxWidth = maxWidth;
	}

	public void setMaxHeight(int maxHeight) {
		this.maxHeight = maxHeight;
	}

	public void setMaxSize(long maxSize) {
		this.maxSize = maxSize;
	}

	/**
	 * @return サポートしている形式か否か
	 */
	public boolean isSupportFormat(final int trackIndex) {
		// TODO
		return true;
	}

	/**
	 * エンコーダの設定に使うフォーマットを作成する。 psd等がないので
	 * {@link MediaMuxer#addTrack(MediaFormat)} に利用したりできない。
	 */
	private MediaFormat createEncoderConfigurationFormat(MediaFormat origin) {
		// TODO アスペクト比を保ったまま、16の倍数になるように width, height を指定する。
		// TODO 回転を考慮に入れる
		final int width = maxWidth > 0 ? maxWidth : origin
				.getInteger(MediaFormat.KEY_WIDTH);
		final int height = maxHeight > 0 ? maxHeight : origin
				.getInteger(MediaFormat.KEY_HEIGHT);
		final MediaFormat format = MediaFormat.createVideoFormat("video/avc",
				width, height);
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		format.setFloat(MediaFormat.KEY_FRAME_RATE, 15); // TODO 外から指定できるようにする
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5); // TODO
																// 外から指定できるようにする

		final long playDuration = Long.valueOf(metadataRetriever
				.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
		int bitrate = (int) (maxSize / playDuration * 8);
		if (bitrate > 1024 * 1024) { // TODO ビットレートが現在のサイズより大きくならないようにする。
			bitrate = 1024 * 1024;
		}
		Log.v(LOG_TAG, "playDuration:" + playDuration + ", bit-rate=" + bitrate);
		format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
		return format;
	}

	public MediaFormat createOutputFormat(final int trackIndex) {
		final MediaFormat currentFormat = extractor.getTrackFormat(trackIndex);
		final MediaFormat newFormat = createEncoderConfigurationFormat(currentFormat);

		final MediaCodec encoder = createEncoder(newFormat);
		final InputSurface inputSurface = new InputSurface(
				encoder.createInputSurface());
		inputSurface.makeCurrent();
		encoder.start();

		final OutputSurface outputSurface = new OutputSurface(-rotation);
		final MediaCodec decoder = createDecoder(currentFormat,
				outputSurface.getSurface());
		decoder.start();

		try {
			extractor.selectTrack(trackIndex);

			final ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
			final MediaCodec.BufferInfo decoderOutputBufferInfo = new MediaCodec.BufferInfo();
			final MediaCodec.BufferInfo encoderOutputBufferInfo = new MediaCodec.BufferInfo();

			boolean extractorDone = false;
			boolean decoderDone = false;

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
					if (decoderOutputBufferIndex < 0) {
						break;
					}
					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						decoder.releaseOutputBuffer(decoderOutputBufferIndex,
								false);
						break;
					}
					final boolean render = decoderOutputBufferInfo.size != 0;
					decoder.releaseOutputBuffer(decoderOutputBufferIndex,
							render);
					if (render) {
						Log.d(LOG_TAG, "output surface: await new image");
						outputSurface.drawNewImage();
						inputSurface
								.setPresentationTime(decoderOutputBufferInfo.presentationTimeUs * 1000);
						Log.d(LOG_TAG, "input surface: swap buffers");
						inputSurface.swapBuffers();
					}
					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "video decoder: EOS");
						decoderDone = true;
						encoder.signalEndOfInputStream();
					}
					break;
				}

				final int encoderOutputBufferIndex = encoder
						.dequeueOutputBuffer(encoderOutputBufferInfo,
								TIMEOUT_USEC);
				if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					Log.d(LOG_TAG,
							"create new format:" + encoder.getOutputFormat());
					return encoder.getOutputFormat();
				}

				if (encoderOutputBufferIndex >= 0) {
					throw new RuntimeException("Can't craete new format.");
				}
			}
		} finally {
			encoder.stop();
			encoder.release();
			inputSurface.release();
			decoder.stop();
			decoder.release();
			outputSurface.release();
		}
	}

	public void shrink(final int trackIndex) {
		final MediaFormat currentFormat = extractor.getTrackFormat(trackIndex);
		final MediaFormat newFormat = createEncoderConfigurationFormat(currentFormat);

		final MediaCodec encoder = createEncoder(newFormat);
		final InputSurface inputSurface = new InputSurface(
				encoder.createInputSurface());
		inputSurface.makeCurrent();
		encoder.start();

		final OutputSurface outputSurface = new OutputSurface(-rotation);
		final MediaCodec decoder = createDecoder(currentFormat,
				outputSurface.getSurface());
		decoder.start();

		try {
			extractor.selectTrack(trackIndex);

			final ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
			final MediaCodec.BufferInfo decoderOutputBufferInfo = new MediaCodec.BufferInfo();

			ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
			final MediaCodec.BufferInfo encoderOutputBufferInfo = new MediaCodec.BufferInfo();

			boolean extractorDone = false;
			boolean decoderDone = false;
			boolean encoderDone = false;

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
					if (decoderOutputBufferIndex < 0) {
						break;
					}
					if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						Log.d(LOG_TAG, "video decoder: output format changed: "
								+ decoder.getOutputFormat());
						break;
					}
					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						decoder.releaseOutputBuffer(decoderOutputBufferIndex,
								false);
						break;
					}
					final boolean render = decoderOutputBufferInfo.size != 0;
					decoder.releaseOutputBuffer(decoderOutputBufferIndex,
							render);
					if (render) {
						Log.d(LOG_TAG, "output surface: await new image");
						outputSurface.drawNewImage();
						inputSurface
								.setPresentationTime(decoderOutputBufferInfo.presentationTimeUs * 1000);
						Log.d(LOG_TAG, "input surface: swap buffers");
						inputSurface.swapBuffers();
					}
					if ((decoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "video decoder: EOS");
						decoderDone = true;
						encoder.signalEndOfInputStream();
					}
					break;
				}

				while (!encoderDone) {
					final int encoderOutputBufferIndex = encoder
							.dequeueOutputBuffer(encoderOutputBufferInfo,
									TIMEOUT_USEC);
					if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						Log.d(LOG_TAG, "video encoder: output buffers changed");
						encoderOutputBuffers = encoder.getOutputBuffers();
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
					if (encoderOutputBufferInfo.size != 0) {
						muxer.writeSampleData(trackIndex, encoderOutputBuffer,
								encoderOutputBufferInfo);
					}
					if ((encoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d(LOG_TAG, "video encoder: EOS");
						encoderDone = true;
					}
					encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
					break;
				}
			}

		} finally {
			encoder.stop();
			encoder.release();
			inputSurface.release();
			decoder.stop();
			decoder.release();
			outputSurface.release();
		}
	}

	private MediaCodec createDecoder(final MediaFormat format,
			final Surface surface) {
		final MediaCodec decoder = MediaCodec.createDecoderByType(format
				.getString(MediaFormat.KEY_MIME));
		decoder.configure(format, surface, null, 0);

		return decoder;
	}

	private MediaCodec createEncoder(final MediaFormat format) {
		final MediaCodec encoder = MediaCodec.createEncoderByType(format
				.getString(MediaFormat.KEY_MIME));
		encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		return encoder;
	}
}
