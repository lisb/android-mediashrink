package com.lisb.android.mediashrink;

import java.io.IOException;
import java.util.concurrent.Executors;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

public class MediaShrinkService extends Service {

	public static final String EXTRA_DEST_FILEPATH = "output";
	public static final String EXTRA_WIDTH = "width";
	public static final String EXTRA_VIDEO_BITRATE = "video-bitrate";
	public static final String EXTRA_AUDIO_BITRATE = "audio-bitrate";
	public static final String EXTRA_DURATION_LIMIT = "duration-limit";

	public static final int REQUEST_SHRINK_MSGID = 1;
	public static final String REQUEST_SHRINK_SOURCE_URI = "input";

	/** arg1 は進捗率 */
	public static final int RESULT_PROGRESS_MSGID = 1;
	public static final int RESULT_COMPLETE_MSGID = 2;
	public static final int RESULT_RECOVERABLE_ERROR_MSGID = 3;
	public static final String RESULT_RECOVERABLE_ERROR_EXCEPTION = "exception";
	public static final int RESULT_UNRECOVERABLE_ERROR_MSGID = 4;
	public static final String RESULT_UNRECOVERABLE_ERROR_EXCEPTION = "exception";

	private static final String LOG_TAG = MediaShrinkService.class
			.getSimpleName();

	private final Object lock = new Object();
	private MediaShrink mediaShrink;
	private Messenger messenger;
	private HandlerThread bgThread;

	@Override
	public void onCreate() {
		Log.v(LOG_TAG, "onCreate");
		super.onCreate();
		bgThread = new HandlerThread("request-queue-thread");
		bgThread.start();
		final ReceiveRequestHandler handler = new ReceiveRequestHandler(
				bgThread.getLooper());
		messenger = new Messenger(handler);

		mediaShrink = new MediaShrink(this);
		mediaShrink.setOnProgressListener(handler);

	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.v(LOG_TAG, "onBind");
		mediaShrink.setOutput(intent.getStringExtra(EXTRA_DEST_FILEPATH));
		mediaShrink.setMaxWidth(intent.getIntExtra(EXTRA_WIDTH, -1));
		mediaShrink
				.setVideoBitRate(intent.getIntExtra(EXTRA_VIDEO_BITRATE, -1));
		mediaShrink
				.setAudioBitRate(intent.getIntExtra(EXTRA_AUDIO_BITRATE, -1));
		mediaShrink.setDurationLimit(intent.getLongExtra(EXTRA_DURATION_LIMIT,
				-1));

		return messenger.getBinder();
	}

	@Override
	public void onDestroy() {
		Log.v(LOG_TAG, "onDestroy");
		super.onDestroy();
		mediaShrink = null;
		messenger = null;
		bgThread.quit();
		bgThread = null;

		Process.killProcess(Process.myPid());
	}

	private class ReceiveRequestHandler extends Handler implements
			OnProgressListener, UnrecoverableErrorCallback {

		private Message currentMessage;

		public ReceiveRequestHandler(Looper looper) {
			super(looper);
		}

		@Override
		public void handleMessage(final Message msg) {
			switch (msg.what) {
			case REQUEST_SHRINK_MSGID:
				currentMessage = msg;
				Executors.newSingleThreadExecutor().execute(new Runnable() {
					@Override
					public void run() {
						try {
							final Uri source = (Uri) currentMessage.getData()
									.getParcelable(REQUEST_SHRINK_SOURCE_URI);
							mediaShrink.shrink(source,
									ReceiveRequestHandler.this);
							respondSafely(Message.obtain(null,
									RESULT_COMPLETE_MSGID));
						} catch (IOException | TooMovieLongException e) {
							Log.e(LOG_TAG, "fail to media shrink", e);
							final Message response = Message.obtain();
							response.what = RESULT_RECOVERABLE_ERROR_MSGID;
							final Bundle data = new Bundle();
							data.putSerializable(
									RESULT_RECOVERABLE_ERROR_EXCEPTION, e);
							response.setData(data);
							respondSafely(response);
						}
						currentMessage = null;
						synchronized (lock) {
							lock.notifyAll();
						}
					}
				});

				while (currentMessage != null) {
					try {
						synchronized (lock) {
							lock.wait();
						}
					} catch (InterruptedException e) {
						Log.e(LOG_TAG, "interrupted", e);
					}
				}

				break;
			}
		}

		private void respondSafely(final Message message) {
			try {
				currentMessage.replyTo.send(message);
			} catch (RemoteException e) {
				Log.e(LOG_TAG, "fail to respond", e);
			}
		}

		@Override
		public void onProgress(int progress) {
			respondSafely(Message.obtain(null, RESULT_PROGRESS_MSGID, progress,
					0));
		}

		@Override
		public void onUnrecoverableError(Throwable e) {
			Log.e(LOG_TAG, "unrecoverable error occured.", e);
			final Message response = Message.obtain();
			response.what = RESULT_UNRECOVERABLE_ERROR_MSGID;
			final Bundle data = new Bundle();
			data.putSerializable(RESULT_UNRECOVERABLE_ERROR_EXCEPTION, e);
			response.setData(data);
			respondSafely(response);
			Process.killProcess(Process.myPid());
		}
	}

}
