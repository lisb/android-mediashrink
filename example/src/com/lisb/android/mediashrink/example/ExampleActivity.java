package com.lisb.android.mediashrink.example;

import java.io.File;
import java.io.IOException;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

import com.lisb.android.mediashrink.MediaShrink;
import com.lisb.android.mediashrink.example.R.id;
import com.lisb.android.mediashrink.example.R.layout;

public class ExampleActivity extends Activity implements OnClickListener {

	private static final String LOG_TAG = ExampleActivity.class.getSimpleName();

	private static final int MAX_WIDTH = 384;
	private static final int VIDEO_BITRATE = 500 * 1024;
	private static final int AUDIO_BITRATE = 128 * 1024;
	
	private static final String EXPORT_DIR = "exports";
	private static final String EXPORT_FILE = "video.mp4";
	private static final String SPREF_SELECTED_FILEPATH = "filepath";

	private static final int RCODE_SELECT_FROM_GALLARY = 1;
	private static final int RCODE_CAPTURE_VIDEO = 1;

	private View progress;
	private TextView txtSelectedVideoPath;
	private View btnStartReencoding;
	private View btnPlaySelectedVideo;
	private View btnPlayReencodedVideo;

	private AsyncTask<Void, Void, Exception> reencodeTask;
	private Uri selectedVideoPath;

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

		final String filepath = getSharedPreferences().getString(
				SPREF_SELECTED_FILEPATH, null);
		if (filepath != null) {
			onFileSelected(Uri.parse(filepath));
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
	}

	@Override
	protected void onPause() {
		super.onPause();

		if (selectedVideoPath != null) {
			final Editor editor = getSharedPreferences().edit();
			editor.putString(SPREF_SELECTED_FILEPATH,
					selectedVideoPath.toString());
			editor.commit();
		}
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
			intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 90);
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
			onFileSelected(data.getData());
		}
	}

	private void onFileSelected(final Uri uri) {
		selectedVideoPath = uri;
		if (uri != null) {
			txtSelectedVideoPath.setText(uri.toString());
			btnPlaySelectedVideo.setEnabled(true);
			btnStartReencoding.setEnabled(true);
		} else {
			txtSelectedVideoPath.setText("");
			btnPlaySelectedVideo.setEnabled(false);
			btnStartReencoding.setEnabled(false);
		}
	}

	private SharedPreferences getSharedPreferences() {
		return PreferenceManager.getDefaultSharedPreferences(this);
	}

	private void reencode() {
		assert reencodeTask == null;

		progress.setVisibility(View.VISIBLE);
		reencodeTask = new AsyncTask<Void, Void, Exception>() {
			@Override
			protected Exception doInBackground(Void... params) {
				final MediaShrink shrink = MediaShrink
						.createMediaShrink(ExampleActivity.this);
				getOutput().getParentFile().mkdirs();
				shrink.setOutput(getOutput().getAbsolutePath());
				shrink.setMaxWidth(MAX_WIDTH);
				shrink.setVideoBitRate(VIDEO_BITRATE);
				shrink.setAudioBitRate(AUDIO_BITRATE);
				try {
					shrink.shrink(selectedVideoPath);
					return null;
				} catch (IOException e) {
					Log.e(LOG_TAG, "MediaShrink failed.", e);
					return e;
				}
			}

			@Override
			protected void onPostExecute(Exception result) {
				super.onPostExecute(result);
				reencodeTask = null;
				progress.setVisibility(View.GONE);

				if (result != null) {
					getOutput().delete();
					btnPlayReencodedVideo.setEnabled(false);
				} else {
					btnPlayReencodedVideo.setEnabled(true);
				}
			}
		};

		reencodeTask.execute();
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
			reencode();
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
