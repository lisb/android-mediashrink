package com.lisb.android.mediashrink;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.jdeferred.Deferred;
import org.jdeferred.Promise;
import org.jdeferred.Promise.State;
import org.jdeferred.impl.DeferredObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;

public class MediaShrinkQueue {

	private static final String LOG_TAG = MediaShrinkQueue.class
			.getSimpleName();

	private static final boolean DEBUG = true;

	private static final String WORKING_DIR = "media-shrink-workspace";
	private static final String WORKING_FILE = "shrinking";

	private static final long DELAY_RETRY_BIND = 1000;
	// delay unbind to reuse service
	private static final long DELAY_UNBIND = 10 * 1000;

	private final Context context;
	private final Handler handler;
	private final Queue<Request> queue = new ArrayDeque<>();
	private final int width;
	private final int videoBitrate;
	private final int audioBitrate;
	private final long durationLimit;
	private final ServiceConnection connection;
	private final Runnable unbindTask;

	private Messenger sendMessenger;
	private Messenger receiveMessenger;
	private boolean bound;
	private boolean unbindInvoked;
	private IBinder binder;

	public static boolean isSupportedDevice(final Context context) {
		return MediaShrink.isSupportedDevice(context);
	}

	public MediaShrinkQueue(Context context, Handler handler, int width,
			int videoBitrate, int audioBitrate, long durationLimit) {
		this.context = context;
		this.handler = handler;
		this.width = width;
		this.videoBitrate = videoBitrate;
		this.audioBitrate = audioBitrate;
		this.durationLimit = durationLimit;
		this.connection = new MediaShrinkServiceConnection();
		this.unbindTask = new UnbindTask();
	}

	public Promise<Void, Exception, Integer> queue(Uri source, File dest) {
		final Deferred<Void, Exception, Integer> deferred = new DeferredObject<>();
		final Request request = new Request(deferred, source, dest);
		handler.post(new Runnable() {
			@Override
			public void run() {
				if (isUnbinding()) {
					handler.postDelayed(this, DELAY_RETRY_BIND);
					return;
				}

				try {
					bindService();
				} catch (IOException e) {
					Log.e(LOG_TAG, "Fail to bind service.", e);
					request.deferred.reject(e);
					return;
				}

				queue.add(request);
				if (sendMessenger != null) {
					sendRequest(request);

				}
			}
		});
		return deferred;
	}

	private void bindService() throws IOException {
		Log.v(LOG_TAG, "bind service.");

		if (binder != null && binder.isBinderAlive()) {
			Log.v(LOG_TAG, "Service bound.");
			return;
		}

		checkWorkingDirAvailable(context);
		final File workingFile = getWorkingFile();

		final Intent intent = new Intent(context, MediaShrinkService.class);
		intent.putExtra(MediaShrinkService.EXTRA_DEST_FILEPATH,
				workingFile.getAbsolutePath());
		intent.putExtra(MediaShrinkService.EXTRA_WIDTH, width);
		intent.putExtra(MediaShrinkService.EXTRA_VIDEO_BITRATE, videoBitrate);
		intent.putExtra(MediaShrinkService.EXTRA_AUDIO_BITRATE, audioBitrate);
		intent.putExtra(MediaShrinkService.EXTRA_DURATION_LIMIT, durationLimit);

		if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
			Log.e(LOG_TAG, "bindService return false.");
			throw new IOException("Fail to connect to MediaShrinkService.");
		}
		bound = true;
	}

	private void unbindServiceIfQueueIsEmpty() {
		if (bound && queue.isEmpty()) {
			Log.v(LOG_TAG, "unbind service.");
			context.unbindService(connection);
			bound = false;
			unbindInvoked = true;
			sendMessenger = null;
			receiveMessenger = null;
		}
	}

	private void unbindServiceIfQueueIsEmptyDelayed() {
		handler.removeCallbacks(unbindTask);
		handler.postDelayed(unbindTask, DELAY_UNBIND);
	}

	@SuppressLint("Assert")
	private boolean sendRequest(final Request r) {
		assert sendMessenger != null;
		assert Looper.myLooper() == handler.getLooper();
		final Message m = Message.obtain(null,
				MediaShrinkService.REQUEST_SHRINK_MSGID);
		final Bundle data = new Bundle();
		data.putParcelable(MediaShrinkService.REQUEST_SHRINK_SOURCE_URI,
				r.source);
		m.setData(data);
		m.replyTo = receiveMessenger;
		try {
			sendMessenger.send(m);
			return true;
		} catch (RemoteException e) {
			Log.e(LOG_TAG, "fail to send request.", e);
			return false;
		}
	}

	private void rebindServiceIfQueueIsNotEmpty() {
		if (!queue.isEmpty()) {

			if (isUnbinding()) {
				handler.postDelayed(new Runnable() {
					@Override
					public void run() {
						rebindServiceIfQueueIsNotEmpty();
					}
				}, DELAY_RETRY_BIND);
				return;
			}

			try {
				bindService();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Fail to reconnect service.", e);
				final Request[] remains = new Request[queue.size()];
				queue.toArray(remains);
				queue.clear();
				for (Request r : remains) {
					r.deferred.reject(e);
				}
			}
		}
	}

	private boolean isUnbinding() {
		if (!unbindInvoked) {
			return false;
		}

		if (binder == null || !binder.pingBinder()) {
			unbindInvoked = false;
			binder = null;
			return false;
		}

		Log.v(LOG_TAG, "isUnbinding");
		return true;
	}

	private static void checkWorkingDirAvailable(final Context context)
			throws ExternalStageNotMounted {
		if (!Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState())) {
			throw new ExternalStageNotMounted("external storage not mounted.");
		}
	}

	private File getWorkingFile() throws IOException {
		final File dir = context.getExternalFilesDir(WORKING_DIR);
		dir.mkdirs();
		if (!dir.isDirectory()) {
			throw new IOException("Can not create Directory.");
		}
		return new File(dir, WORKING_FILE);
	}

	private class MediaShrinkServiceConnection implements ServiceConnection {
		@Override
		public void onServiceConnected(final ComponentName name,
				final IBinder service) {
			Log.d(LOG_TAG, "onServiceConnected.");
			handler.post(new Runnable() {
				@Override
				public void run() {
					binder = service;
					sendMessenger = new Messenger(service);
					receiveMessenger = new Messenger(new ReceiveResultHandler(
							handler.getLooper()));
					Log.v(LOG_TAG, "send all requests. size:" + queue.size());
					for (Request r : queue) {
						if (!sendRequest(r)) {
							// rebind はイベントに任せる
							break;
						}
					}
				}
			});
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.e(LOG_TAG, "onServiceDisconnected.");
			handler.post(new Runnable() {
				@Override
				public void run() {
					final Request request = queue.poll();
					if (request != null
							&& request.deferred.state() == State.PENDING) {
						request.deferred.reject(new RuntimeException(
								"process killed."));
					}
					bound = false;
					sendMessenger = null;
					receiveMessenger = null;
					rebindServiceIfQueueIsNotEmpty();
				}
			});
		}
	}

	private class ReceiveResultHandler extends Handler {

		public ReceiveResultHandler(Looper looper) {
			super(looper);
		}

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MediaShrinkService.RESULT_COMPLETE_MSGID: {
				// 動画圧縮の途中でプロセスがkillされた際にゴミファイルが残らないように
				// 圧縮中は作業ファイルに出力し圧縮完了後に出力先に移動するという手順を取る。
				final Request request = queue.poll();
				try {
					getWorkingFile().renameTo(request.dest);
					request.deferred.resolve(null);
				} catch (IOException e) {
					Log.e(LOG_TAG, "fail to rename temp file to dest file.", e);
					request.deferred.reject(e);
				}

				if (DEBUG) {
					Log.v(LOG_TAG, "RESULT_COMPLETE_MSGID");
				}

				unbindServiceIfQueueIsEmptyDelayed();
				break;
			}
			case MediaShrinkService.RESULT_RECOVERABLE_ERROR_MSGID: {
				final Request request = queue.poll();
				request.deferred
						.reject((Exception) msg
								.getData()
								.getSerializable(
										MediaShrinkService.RESULT_RECOVERABLE_ERROR_EXCEPTION));
				unbindServiceIfQueueIsEmptyDelayed();
				break;
			}
			case MediaShrinkService.RESULT_UNRECOVERABLE_ERROR_MSGID: {
				// rebind や request のキューからの除去は onServiceDisconnected に任せる
				final Request request = queue.peek();
				request.deferred
						.reject((Exception) msg
								.getData()
								.getSerializable(
										MediaShrinkService.RESULT_UNRECOVERABLE_ERROR_EXCEPTION));
				unbindServiceIfQueueIsEmpty();
				break;
			}
			case MediaShrinkService.RESULT_PROGRESS_MSGID: {
				final Request request = queue.peek();
				request.deferred.notify(msg.arg1);
				break;
			}
			}
		}
	}

	private class UnbindTask implements Runnable {
		@Override
		public void run() {
			unbindServiceIfQueueIsEmpty();
		}
	}

	private static class Request {
		public final Deferred<Void, Exception, Integer> deferred;
		public final Uri source;
		public final File dest;

		public Request(Deferred<Void, Exception, Integer> deferred, Uri source,
				File dest) {
			this.deferred = deferred;
			this.source = source;
			this.dest = dest;
		}

	}
}
