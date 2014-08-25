package com.lisb.android.mediashrink;

import java.io.IOException;
import java.nio.ByteBuffer;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

public class MediaShrink {

	private static final String LOG_TAG = MediaShrink.class.getSimpleName();

	private final Context context;

	private int maxWidth = -1;
	private int maxHeight = -1;
	private long maxLength = -1;
	private long maxSize = 0;
	private String output;

	public static MediaShrink createMediaShrink(final Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
			return null;
		}

		if (!OpenglUtils.supportsOpenglEs2(context)) {
			return null;
		}

		return new MediaShrink(context);
	}

	public MediaShrink(Context context) {
		this.context = context;
	}

	public void shrink(final Uri input) throws IOException {

		MediaExtractor extractor = null;
		MediaMetadataRetriever metadataRetriever = null;
		MediaMuxer muxer = null;
		VideoShrink videoShrink = null;

		try {
			extractor = new MediaExtractor();
			metadataRetriever = new MediaMetadataRetriever();

			// TODO ビデオが maxLength より長い場合、エラーを返す。
			// TODO トラックにビデオ、オーディオがそれぞれ2つ以上設定されていた場合、エラーを返す。(MediaMuxerが対応していない)
			// TODO デコードできないビデオやオーディオがあった場合、エラーを返す。

			try {
				extractor.setDataSource(context, input, null);
				metadataRetriever.setDataSource(context, input);
			} catch (IOException e) {
				// TODO 多言語化
				Log.e(LOG_TAG, "Reading input is failed.", e);
				throw new IOException("指定された動画ファイルの読み込みに失敗しました。", e);
			}

			try {
				muxer = new MediaMuxer(output,
						MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			} catch (IOException e) {
				// TODO 多言語化
				Log.e(LOG_TAG, "Writing output is failed.", e);
				throw new IOException("出力ファイルの作成に失敗しました。", e);
			}

			// トラックの作成。
			for (int i = 0, length = extractor.getTrackCount(); i < length; i++) {
				final MediaFormat format = extractor.getTrackFormat(i);
				Log.d(LOG_TAG, "track [" + i + "] format: " + format);
				if (isVideoFormat(format)) {
					if (videoShrink == null) {
						videoShrink = new VideoShrink(extractor,
								metadataRetriever, muxer);
						videoShrink.setMaxWidth(maxWidth);
						videoShrink.setMaxHeight(maxHeight);
						videoShrink.setMaxSize(maxSize); // TODO
															// audio分のサイズを差し引く。
					}
					muxer.addTrack(videoShrink.createOutputFormat(i));
				} else {
					muxer.addTrack(format);
				}
			}

			muxer.start();

			// 実際のデータの書き込み
			for (int i = 0, length = extractor.getTrackCount(); i < length; i++) {
				final MediaFormat format = extractor.getTrackFormat(i);
				if (isVideoFormat(format)) {
					videoShrink.shrink(i);
				} else {
					copyTrack(extractor, muxer, i);
				}
			}

		} finally {
			if (extractor != null) {
				extractor.release();
			}

			if (metadataRetriever != null) {
				metadataRetriever.release();
			}

			if (muxer != null) {
				muxer.stop();
				muxer.release();
			}
		}
	}

	private void copyTrack(final MediaExtractor extractor,
			final MediaMuxer muxer, final int trackIndex) {
		final ByteBuffer byteBuf = ByteBuffer.allocate(1000 * 1000); // TODO
																		// バッファのサイズを調整
		final BufferInfo bufInfo = new BufferInfo();

		extractor.selectTrack(trackIndex);
		int size;
		while ((size = extractor.readSampleData(byteBuf, 0)) != -1) {
			bufInfo.offset = 0;
			bufInfo.flags = extractor.getSampleFlags();
			bufInfo.presentationTimeUs = extractor.getSampleTime();
			bufInfo.size = size;
			muxer.writeSampleData(trackIndex, byteBuf, bufInfo);
			extractor.advance();
		}

		bufInfo.offset = 0;
		bufInfo.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
		bufInfo.presentationTimeUs = 0;
		bufInfo.size = 0;
		muxer.writeSampleData(trackIndex, byteBuf, bufInfo);
	}

	private boolean isVideoFormat(MediaFormat format) {
		return format.getString(MediaFormat.KEY_MIME).startsWith("video/");
	}

	/**
	 * このサイズを超えないように縮小をかける。 (設定必須)
	 */
	public void setMaxSize(long maxSize) {
		this.maxSize = maxSize;
	}

	/**
	 * 出力先の指定。(設定必須)
	 */
	public void setOutput(String output) {
		this.output = output;
	}

	/**
	 * @param maxWidth
	 *            0以下の時、無視される。
	 */
	public void setMaxWidth(int maxWidth) {
		this.maxWidth = maxWidth;
	}

	/**
	 * @param maxHeight
	 *            0以下の時、無視される
	 */
	public void setMaxHeight(int maxHeight) {
		this.maxHeight = maxHeight;
	}

	/**
	 * ビデオの最大の長さ。 この長さを越えるビデオのエンコードは行わない。
	 * 
	 * @param maxLength
	 *            0以下の時、無視される。
	 */
	public void setMaxLength(int maxLength) {
		this.maxLength = maxLength;
	}

}
