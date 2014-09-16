package com.lisb.android.mediashrink;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

public class MediaShrink {

	private static final String LOG_TAG = MediaShrink.class.getSimpleName();

	private static final int PROGRESS_DECODABLE_CHECKED = 50;
	private static final int PROGRESS_ADD_TRACK = 10;
	private static final int PROGRESS_WRITE_CONTENT = 40;

	private static final long UPDATE_CHECK_DECODABLE_PROGRESS_INTERVAL_MS = 3 * 1000;

	private final Context context;

	private int maxWidth = -1;
	private long durationLimit = -1;
	private int audioBitRate;
	private int videoBitRate;
	private String output;
	private OnProgressListener onProgressListener;

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

	public void shrink(final Uri src, final boolean checkSource)
			throws IOException, DecodeException, TooMovieLongException {
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

			try {
				extractor.setDataSource(context, src, null);
				metadataRetriever.setDataSource(context, src);
			} catch (IOException e) {
				// TODO 多言語化
				Log.e(LOG_TAG, "Reading input is failed.", e);
				throw new IOException("指定された動画ファイルの読み込みに失敗しました。", e);
			}

			checkLength(metadataRetriever);

			int maxProgress = 0;
			int progress = 0;

			// プログレスの計算のため、圧縮するトラックの数を前もって数える
			Integer videoTrack = null;
			Integer audioTrack = null;
			for (int i = 0, length = extractor.getTrackCount(); i < length; i++) {
				final MediaFormat format = extractor.getTrackFormat(i);
				Log.d(LOG_TAG,
						"track [" + i + "] format: " + Utils.toString(format));
				if (isVideoFormat(format)) {
					if (videoTrack != null) {
						// MediaMuxer がビデオ、オーディオそれぞれ1つずつしか含めることができないため。
						Log.w(LOG_TAG,
								"drop track. support one video track only. track:"
										+ i);
						continue;
					}
					videoTrack = i;
					maxProgress += PROGRESS_ADD_TRACK + PROGRESS_WRITE_CONTENT;
				} else if (isAudioFormat(format)) {
					if (audioTrack != null) {
						// MediaMuxer がビデオ、オーディオそれぞれ1つずつしか含めることができないため。
						Log.w(LOG_TAG,
								"drop track. support one audio track only. track:"
										+ i);
						continue;
					}
					audioTrack = i;
					maxProgress += PROGRESS_ADD_TRACK + PROGRESS_WRITE_CONTENT;
				} else {
					Log.e(LOG_TAG, "drop track. unsupported format. track:" + i
							+ ", format:" + Utils.toString(format));
				}
			}

			if (checkSource) {
				// 時間がかかる処理なので checkLength の後に行う
				maxProgress += PROGRESS_DECODABLE_CHECKED;
				checkDecodable(src, maxProgress);
				progress += PROGRESS_DECODABLE_CHECKED;
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
			Integer newVideoTrack = null;
			if (videoTrack != null) {
				videoShrink = new VideoShrink(extractor, metadataRetriever,
						muxer);
				videoShrink.setMaxWidth(maxWidth);
				videoShrink.setBitRate(videoBitRate);
				newVideoTrack = muxer.addTrack(videoShrink
						.createOutputFormat(videoTrack));

				progress += PROGRESS_ADD_TRACK;
				deliverProgress(progress, maxProgress);
			}
			Integer newAudioTrack = null;
			if (audioTrack != null) {
				audioShrink = new AudioShrink(extractor, muxer);
				audioShrink.setBitRate(audioBitRate);
				newAudioTrack = muxer.addTrack(audioShrink
						.createOutputFormat(audioTrack));

				progress += PROGRESS_ADD_TRACK;
				deliverProgress(progress, maxProgress);
			}

			muxer.start();

			// コンテンツの作成
			if (videoShrink != null) {
				// TODO ビデオ圧縮の進捗を詳細に取れるようにする
				videoShrink.shrink(videoTrack, newVideoTrack);

				progress += PROGRESS_WRITE_CONTENT;
				deliverProgress(progress, maxProgress);
			}
			if (audioShrink != null) {
				// TODO オーディオ圧縮の進捗を詳細に取れるようにする
				audioShrink.shrink(audioTrack, newAudioTrack);

				progress += PROGRESS_WRITE_CONTENT;
				deliverProgress(progress, maxProgress);
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

	private void deliverProgress(int progress, int maxProgress) {
		if (onProgressListener != null) {
			onProgressListener.onProgress(progress * 100 / maxProgress);
		}
	}

	private void checkDecodable(final Uri uri, final int maxProgress)
			throws IOException, DecodeException {
		final MediaPlayer player = new MediaPlayer();
		final Object lock = new Object();
		final AtomicReference<Boolean> successRef = new AtomicReference<Boolean>(
				null);
		final int[] textures = new int[1];
		GLES20.glGenTextures(1, textures, 0);
		final SurfaceTexture surfaceTexture = new SurfaceTexture(textures[0]);
		final Surface surface = new Surface(surfaceTexture);

		try {
			player.setDataSource(context, uri);
			player.setSurface(surface);
			player.setVolume(0f, 0f);

			player.setOnErrorListener(new OnErrorListener() {
				@Override
				public boolean onError(MediaPlayer mp, int what, int extra) {
					Log.e(LOG_TAG, "fail to play on MediaPlayer.");
					synchronized (lock) {
						successRef.set(false);
						lock.notifyAll();
					}
					return true;
				}
			});
			player.setOnCompletionListener(new OnCompletionListener() {
				@Override
				public void onCompletion(MediaPlayer mp) {
					Log.d(LOG_TAG, "complete to play on MediaPlayer.");
					synchronized (lock) {
						successRef.set(true);
						lock.notifyAll();
					}
				}
			});

			player.prepare();
			player.start();

			synchronized (lock) {
				while (successRef.get() == null) {
					try {
						lock.wait(UPDATE_CHECK_DECODABLE_PROGRESS_INTERVAL_MS);
						if (player.isPlaying()) {
							deliverProgress(
									player.getCurrentPosition()
											* PROGRESS_DECODABLE_CHECKED
											/ player.getDuration(), maxProgress);
						}
					} catch (InterruptedException e) {
						Log.e(LOG_TAG, "player lock is interrupted.", e);
					}
				}
			}

			if (player.isPlaying()) {
				player.stop();
			}

			if (!successRef.get()) {
				throw new DecodeException("These movie is not decodable.");
			}
		} finally {
			if (player.isPlaying()) {
				player.stop();
			}
			player.release();

			surface.release();
			surfaceTexture.release();
		}
	}

	private void checkLength(final MediaMetadataRetriever metadataRetriever)
			throws TooMovieLongException {
		if (durationLimit <= 0) {
			return;
		}

		final long durationSec = Long.valueOf(metadataRetriever
				.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
		if (durationSec > durationLimit) {
			Log.e(LOG_TAG, "movie duration (" + durationSec
					+ " sec)is longer than duration limit(" + durationLimit
					+ " sec). ");
			throw new TooMovieLongException("movie duration (" + durationSec
					+ " sec)is longer than duration limit(" + durationLimit
					+ " sec). ");
		}
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
	 * Nexus 7(2013) ではある幅以外だとエンコード結果がおかしくなるので 幅を固定して使うことになる。
	 * 幅を固定する以外のうまい方法が見つかるまではこのメソッドの使用不可にする。
	 */
	// public void setMaxHeight(int maxHeight) {
	// this.maxHeight = maxHeight;
	// }

	public void setOnProgressListener(OnProgressListener listener) {
		this.onProgressListener = listener;
	}

	/**
	 * ビデオの最大の長さ。 この長さを越えるビデオのエンコードは行わない。
	 * 
	 * @param durationLimit
	 *            0以下の時、無視される。
	 */
	public void setDurationLimit(long durationLimit) {
		this.durationLimit = durationLimit;
	}

	public interface OnProgressListener {
		void onProgress(int progress);
	}
}
