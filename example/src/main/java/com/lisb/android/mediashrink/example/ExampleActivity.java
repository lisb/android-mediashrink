package com.lisb.android.mediashrink.example;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

import com.lisb.android.mediashrink.MediaShrinkQueue;
import com.lisb.android.mediashrink.example.R.id;
import com.lisb.android.mediashrink.example.R.layout;

import org.jdeferred.DoneCallback;
import org.jdeferred.FailCallback;
import org.jdeferred.Promise;

import java.io.File;

import timber.log.Timber;

public class ExampleActivity extends Activity implements OnClickListener {

	private static final String TAG = "ExampleActivity";

	private static final int MAX_WIDTH = 384;
	private static final int VIDEO_BITRATE = 500 * 1024;
	private static final int AUDIO_BITRATE = 128 * 1024;
	private static final long DURATION_LIMIT = 90;

	private static final String EXPORT_DIR = "exports";
	private static final String EXPORT_FILE = "video.mp4";
	private static final String SPREF_SELECTED_FILEPATH = "filepath";

	private static final int RCODE_CAPTURE_VIDEO = 1;
	private static final int RCODE_SELECT_FROM_GALLARY = 2;

	private View progress;
	private TextView txtSelectedVideoPath;
	private View btnStartReencoding;
	private View btnPlaySelectedVideo;
	private View btnPlayReencodedVideo;

	private Uri selectedVideoPath;
	private MediaShrinkQueue mediaShrinkQueue;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(layout.activity_example);

		// wiring
		final View btnCaptureVideo = findViewById(id.btn_select_video);
		registerForContextMenu(btnCaptureVideo);
		btnCaptureVideo.setOnClickListener(this);

		btnPlaySelectedVideo = findViewById(id.btn_play_selected_video);
		btnPlaySelectedVideo.setOnClickListener(this);

		btnStartReencoding = findViewById(id.btn_start_reencoding);
		btnStartReencoding.setOnClickListener(this);

		btnPlayReencodedVideo = findViewById(id.btn_play_reencoded_video);
		btnPlayReencodedVideo.setOnClickListener(this);

		progress = findViewById(android.R.id.progress);
		txtSelectedVideoPath = (TextView) findViewById(id.txt_selected_video_path);

		final SharedPreferences prefs = getSharedPreferences();
		final String filepath = prefs.getString(SPREF_SELECTED_FILEPATH, null);
		if (filepath != null) {
			onFileSelected(Uri.parse(filepath), false);
		}

		if (getOutput().exists()) {
			btnPlayReencodedVideo.setEnabled(true);
		}

		if (!Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState())) {
			// No External Storage
			txtSelectedVideoPath.setText("External storage is not mounted.");
			btnCaptureVideo.setEnabled(false);
			btnPlaySelectedVideo.setEnabled(false);
			btnStartReencoding.setEnabled(false);
			btnPlayReencodedVideo.setEnabled(false);

			Toast.makeText(this, "Please mount external storage.",
					Toast.LENGTH_LONG).show();
		}

		mediaShrinkQueue = new MediaShrinkQueue(this, new Handler(), getFilesDir(),
				MAX_WIDTH, VIDEO_BITRATE, AUDIO_BITRATE, DURATION_LIMIT);
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		switch (v.getId()) {
		case id.btn_select_video:
			getMenuInflater().inflate(R.menu.context_menu_select_video, menu);
		}
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case id.select_from_gallary: {
			final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
			intent.setType("video/*");
			if (intent.resolveActivity(getPackageManager()) != null) {
				startActivityForResult(intent, RCODE_SELECT_FROM_GALLARY);
			} else {
				Toast.makeText(this, "Activity Not Found.", Toast.LENGTH_SHORT)
						.show();
			}
			break;
		}
		case id.capture_video: {
			final Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
			intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, DURATION_LIMIT);
			intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
			if (intent.resolveActivity(getPackageManager()) != null) {
				startActivityForResult(intent, RCODE_CAPTURE_VIDEO);
			} else {
				Toast.makeText(this, "Activity Not Found.", Toast.LENGTH_SHORT)
						.show();
			}
			break;
		}
		}

		return super.onContextItemSelected(item);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		switch (resultCode) {
		case RESULT_OK:
			onFileSelected(data.getData(), true);
		}
	}

	private void onFileSelected(final Uri uri, final boolean savePreference) {
		this.selectedVideoPath = uri;

		if (uri != null) {
			txtSelectedVideoPath.setText(uri.toString());
			btnPlaySelectedVideo.setEnabled(true);
			btnStartReencoding.setEnabled(true);
		} else {
			txtSelectedVideoPath.setText("");
			btnPlaySelectedVideo.setEnabled(false);
			btnStartReencoding.setEnabled(false);
		}

		if (savePreference) {
			final Editor editor = getSharedPreferences().edit();
			if (uri != null) {
				editor.putString(SPREF_SELECTED_FILEPATH,
						selectedVideoPath.toString());
			} else {
				editor.remove(SPREF_SELECTED_FILEPATH);
			}
			editor.commit();
		}
	}

	private SharedPreferences getSharedPreferences() {
		return PreferenceManager.getDefaultSharedPreferences(this);
	}

	private void shrink() {
		progress.setVisibility(View.VISIBLE);
		getOutput().getParentFile().mkdirs();
		final Promise<Long, Exception, Integer> promise = mediaShrinkQueue
				.queue(selectedVideoPath, Uri.fromFile(getOutput()));
		promise.then(new DoneCallback<Long>() {
			@Override
			public void onDone(Long result) {
				progress.setVisibility(View.GONE);
				btnPlayReencodedVideo.setEnabled(true);
				Toast.makeText(ExampleActivity.this, "Success!",
						Toast.LENGTH_SHORT).show();
			}
		}).fail(new FailCallback<Exception>() {
			@Override
			public void onFail(Exception result) {
				Timber.tag(TAG).e(result, "Failed to shrink media.");

				progress.setVisibility(View.GONE);
				btnPlayReencodedVideo.setEnabled(true);
				getOutput().delete();

				String errorMessage = result.getMessage();
				if (errorMessage == null || errorMessage.isEmpty()) {
					errorMessage = "Failed to shrink.";
				}

				Toast.makeText(ExampleActivity.this, errorMessage,
						Toast.LENGTH_SHORT).show();
			}
		});
	}

	private File getOutput() {
		return new File(getExternalFilesDir(EXPORT_DIR), EXPORT_FILE);
	}

	private void playVideo(Uri uri) {
		final Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setDataAndType(uri, "video/*");
		startActivity(intent);
	}

	// ===== OnClickListener ===== //

	@Override
	public void onClick(View v) {
		switch (v.getId()) {
		case id.btn_select_video:
			v.showContextMenu();
			break;
		case id.btn_start_reencoding:
			shrink();
			break;
		case id.btn_play_selected_video:
			playVideo(selectedVideoPath);
			break;
		case id.btn_play_reencoded_video:
			playVideo(Uri.fromFile(getOutput()));
			break;
		}

	}

}
