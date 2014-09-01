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
	private int audioBitRate;
	private int videoBitRate;
	private String output;

	public static boolean isSupportedDevice(final Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
			return false;
		}

		if (!OpenglUtils.supportsOpenglEs2(context)) {
			return false;
		}
		
		return true;
	}
	
	public static MediaShrink createMediaShrink(final Context context) {
		if (!isSupportedDevice(context)) {
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
		AudioShrink audioShrink = null;

		// デコード・エンコードで発生した RuntimeException が
		// 終了処理中の RuntimeException に上書きされないように保存する。
		RuntimeException re = null;

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
				Log.d(LOG_TAG, "track [" + i + "] format: " + Utils.toString(format));
				if (isVideoFormat(format)) {
					if (videoShrink == null) {
						videoShrink = new VideoShrink(extractor,
								metadataRetriever, muxer);
						videoShrink.setMaxWidth(maxWidth);
//						videoShrink.setMaxHeight(maxHeight);
						videoShrink.setBitRate(videoBitRate);
					}
					muxer.addTrack(videoShrink.createOutputFormat(i));
				} else if (isAudioFormat(format)) {
					if (audioShrink == null) {
						audioShrink = new AudioShrink(extractor, muxer);
						audioShrink.setBitRate(audioBitRate);
					}
					muxer.addTrack(audioShrink.createOutputFormat(i));
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
				} else if (isAudioFormat(format)) {
					audioShrink.shrink(i);
				} else {
					copyTrack(extractor, muxer, i);
				}
			}
		} catch (RuntimeException e) {
			Log.e(LOG_TAG, "fail to shrink.", e);
			re = e;
		} finally {
			try {
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
			} catch (RuntimeException e) {
				Log.e(LOG_TAG, "fail to finalize shrink.", e);
				if (re == null) {
					re = e;
				}
			}
		}

		if (re != null) {
			throw re;
		}
	}

	private void copyTrack(final MediaExtractor extractor,
			final MediaMuxer muxer, final int trackIndex) {
		// TODO バッファのサイズを調整
		final ByteBuffer byteBuf = ByteBuffer.allocate(1024 * 1024);
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

		extractor.unselectTrack(trackIndex);
	}

	private boolean isVideoFormat(MediaFormat format) {
		return format.getString(MediaFormat.KEY_MIME).startsWith("video/");
	}

	private boolean isAudioFormat(MediaFormat format) {
		return format.getString(MediaFormat.KEY_MIME).startsWith("audio/");
	}

	/**
	 * 出力先の指定。(設定必須)
	 */
	public void setOutput(String output) {
		this.output = output;
	}

	/**
	 * 設定必須
	 */
	public void setVideoBitRate(int videoBitRate) {
		this.videoBitRate = videoBitRate;
	}

	/**
	 * 設定必須
	 */
	public void setAudioBitRate(int audioBitRate) {
		this.audioBitRate = audioBitRate;
	}

	/**
	 * Warning: Nexus 7 では決まった幅(640, 384など)でないとエンコード結果がおかしくなる。
	 * セットした値で正しくエンコードできるかテストすること。
	 * 
	 * @param maxWidth
	 *            0以下の時、無視される。
	 */
	public void setMaxWidth(int maxWidth) {
		this.maxWidth = maxWidth;
	}

	/*
	 * Nexus 7(2013) ではある幅以外だとエンコード結果がおかしくなるので
	 * 幅を固定して使うことになる。
	 * 幅を固定する以外のうまい方法が見つかるまではこのメソッドの使用不可にする。
	 */
//	public void setMaxHeight(int maxHeight) {
//		this.maxHeight = maxHeight;
//	}

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
