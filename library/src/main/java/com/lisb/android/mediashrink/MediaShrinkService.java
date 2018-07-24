package com.lisb.android.mediashrink;

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

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaShrinkService extends Service {

	public static final String EXTRA_DEST_FILEPATH = "output";
	public static final String EXTRA_WIDTH = "width";
	public static final String EXTRA_VIDEO_BITRATE = "video-bitrate";
	public static final String EXTRA_AUDIO_BITRATE = "audio-bitrate";
	public static final String EXTRA_DURATION_LIMIT = "duration-limit";

	public static final int REQUEST_SHRINK_MSGID = 1;
	public static final String REQUEST_SHRINK_INPUT_URI = "input";
	public static final String REQUEST_SHRINK_OUTPUT_PATH = "output";

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
	private ExecutorService executorService;

	@Override
	public void onCreate() {
		Log.v(LOG_TAG, "onCreate");
		super.onCreate();

		executorService = Executors.newSingleThreadExecutor();

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
		mediaShrink.setWidth(intent.getIntExtra(EXTRA_WIDTH, -1));
		mediaShrink
				.setVideoBitRate(intent.getIntExtra(EXTRA_VIDEO_BITRATE, -1));
		mediaShrink
				.setAudioBitRate(intent.getIntExtra(EXTRA_AUDIO_BITRATE, -1));
		mediaShrink.setDurationLimit(intent.getLongExtra(EXTRA_DURATION_LIMIT,
				-1));

		return messenger.getBinder();
	}

	@Override
	public boolean onUnbind(Intent intent) {
		Log.v(LOG_TAG, "onUnbind");
		return super.onUnbind(intent);
	}

	@Override
	public void onDestroy() {
		Log.v(LOG_TAG, "onDestroy");
		super.onDestroy();
		mediaShrink = null;
		messenger = null;
		bgThread.quit();
		bgThread = null;
		executorService.shutdown();
		executorService = null;

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
			Log.d(LOG_TAG, "ReceiveRequestHandler#handleMessage. what:" + msg.what);
			switch (msg.what) {
			case REQUEST_SHRINK_MSGID:
				currentMessage = msg;
				executorService.execute(new Runnable() {
					@Override
					public void run() {
						try {
							final Uri inputUri = currentMessage.getData()
									.getParcelable(REQUEST_SHRINK_INPUT_URI);
							final String outputPath = currentMessage.getData()
                                    .getString(REQUEST_SHRINK_OUTPUT_PATH);
							mediaShrink.shrink(inputUri, outputPath,
									ReceiveRequestHandler.this);
							respondSafely(Message.obtain(null,
									RESULT_COMPLETE_MSGID));
						} catch (IOException | TooMovieLongException e) {
							Log.e(LOG_TAG, "Failed to media shrink", e);
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
				Log.d(LOG_TAG, "respondSafely. type:" + message.what);
				currentMessage.replyTo.send(message);
			} catch (RemoteException e) {
				Log.e(LOG_TAG, "Failed to respond", e);
			}
		}

		@Override
		public void onProgress(int progress) {
			respondSafely(Message.obtain(null, RESULT_PROGRESS_MSGID, progress,
					0));
		}

		@Override
		public void onUnrecoverableError(Throwable e) {
			Log.e(LOG_TAG, "Unrecoverable error occurred.", e);
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
