package com.lisb.android.mediashrink;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;

import org.jdeferred.Deferred;
import org.jdeferred.Promise;
import org.jdeferred.Promise.State;
import org.jdeferred.impl.DeferredObject;

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

public class MediaShrinkQueue {

	private static final String LOG_TAG = MediaShrinkQueue.class
			.getSimpleName();

	private static final String WORKING_DIR = "media-shrink-workspace";
	private static final String WORKING_FILE = "shrinking";

	private final Context context;
	private final Handler handler;
	private final Queue<Request> queue = new ArrayDeque<>();
	private final int width;
	private final int videoBitrate;
	private final int audioBitrate;
	private final long durationLimit;

	private ServiceConnection connection;
	private Messenger sendMessenger;
	private Messenger receiveMessenger;

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
	}

	public Promise<Void, Exception, Integer> queue(Uri source, File dest) {
		final Deferred<Void, Exception, Integer> deferred = new DeferredObject<>();
		final Request request = new Request(deferred, source, dest);
		handler.post(new Runnable() {
			@Override
			public void run() {
				try {
					bindServiceIfNeed();
				} catch (IOException e) {
					Log.e(LOG_TAG, "fail to bind service.", e);
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

	private void bindServiceIfNeed() throws IOException {
		if (connection == null) {
			Log.v(LOG_TAG, "bind service.");
			connection = new MediaShrinkServiceConnection();
			final Intent intent = new Intent(context, MediaShrinkService.class);
			intent.putExtra(MediaShrinkService.EXTRA_DEST_FILEPATH,
					getWorkingFile().getAbsolutePath());
			intent.putExtra(MediaShrinkService.EXTRA_WIDTH, width);
			intent.putExtra(MediaShrinkService.EXTRA_VIDEO_BITRATE,
					videoBitrate);
			intent.putExtra(MediaShrinkService.EXTRA_AUDIO_BITRATE,
					audioBitrate);
			intent.putExtra(MediaShrinkService.EXTRA_DURATION_LIMIT,
					durationLimit);
			context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
		}
	}

	private void unbindServiceIfNeed() {
		if (queue.isEmpty()) {
			Log.v(LOG_TAG, "unbind service.");
			context.unbindService(connection);
			connection = null;
			sendMessenger = null;
			receiveMessenger = null;
		}
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

	private void rebindServiceIfNeed() {
		if (!queue.isEmpty()) {
			try {
				bindServiceIfNeed();
			} catch (IOException e) {
				Log.e(LOG_TAG, "fail to reconnect service.", e);
				final Request[] remains = new Request[queue.size()];
				queue.toArray(remains);
				queue.clear();
				for (Request r : remains) {
					r.deferred.reject(e);
				}
			}
		}
	}

	public static boolean isWorkingDirAvailable(final Context context) {
		return Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState());
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

					if (connection != null) {
						// 相手方のサービスが自動で復元してしまうことがあるので明示的に unbind しておく。
						context.unbindService(connection);
						connection = null;
						sendMessenger = null;
						receiveMessenger = null;
					}

					rebindServiceIfNeed();
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
				unbindServiceIfNeed();
				break;
			}
			case MediaShrinkService.RESULT_RECOVERABLE_ERROR_MSGID: {
				final Request request = queue.poll();
				request.deferred
						.reject((Exception) msg
								.getData()
								.getSerializable(
										MediaShrinkService.RESULT_RECOVERABLE_ERROR_EXCEPTION));
				unbindServiceIfNeed();
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
				unbindServiceIfNeed();
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
