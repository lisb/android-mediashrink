package com.lisb.android.mediashrink.example

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.lisb.android.mediashrink.MediaShrinkQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.coroutines.CoroutineContext

class ExampleActivity : AppCompatActivity(), View.OnClickListener, CoroutineScope {
    private lateinit var progress: View
    private lateinit var txtSelectedVideoPath: TextView
    private lateinit var btnStartReencoding: View
    private lateinit var btnPlaySelectedVideo: View
    private lateinit var btnPlayReencodedVideo: View
    private lateinit var mediaShrinkQueue: MediaShrinkQueue
    private lateinit var job: Job
    private var selectedVideoUri: Uri? = null

    private val selectVideoLauncher =
        registerForActivityResult(object: ActivityResultContracts.GetContent() {
            override fun createIntent(context: Context, input: String): Intent {
                return super.createIntent(context, input).apply {
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                }
            }
        }) {
            onFileSelected(it)
        }

    private val captureVideoLauncher =
        registerForActivityResult(object: ActivityResultContract<Unit, Uri?>(){
            override fun createIntent(context: Context, input: Unit): Intent {
                return Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_DURATION_LIMIT, DURATION_LIMIT)
                    putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                }
            }
            override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
                return intent?.data
            }
        }) {
            onFileSelected(it)
        }

    private val outputDir: File
        get() = File(filesDir, EXPORT_DIR)
    private val outputFile: File
        get() = File(outputDir, EXPORT_FILE)

    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        setContentView(R.layout.activity_example)
        // wiring
        val btnCaptureVideo = findViewById<View>(R.id.btn_select_video)
        registerForContextMenu(btnCaptureVideo)
        btnCaptureVideo.setOnClickListener(this)
        btnPlaySelectedVideo = findViewById(R.id.btn_play_selected_video)
        btnPlaySelectedVideo.setOnClickListener(this)
        btnStartReencoding = findViewById(R.id.btn_start_reencoding)
        btnStartReencoding.setOnClickListener(this)
        btnPlayReencodedVideo = findViewById(R.id.btn_play_reencoded_video)
        btnPlayReencodedVideo.setOnClickListener(this)
        progress = findViewById(android.R.id.progress)
        txtSelectedVideoPath = findViewById(R.id.txt_selected_video_path)
        if (savedInstanceState != null) {
            val selectedUri = savedInstanceState.getParcelable<Uri>(SAVED_SELECTED_URI)
            selectedUri?.let { onFileSelected(it) }
        }
        if (outputFile.exists()) {
            btnPlayReencodedVideo.isEnabled = true
        }
        mediaShrinkQueue = MediaShrinkQueue(this.applicationContext, Handler(), filesDir,
                MAX_WIDTH, VIDEO_BITRATE, AUDIO_BITRATE, DURATION_LIMIT)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(SAVED_SELECTED_URI, selectedVideoUri)
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        if (v.id == R.id.btn_select_video) {
            menuInflater.inflate(R.menu.context_menu_select_video, menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.select_from_gallary -> {
                try {
                    selectVideoLauncher.launch("video/*")
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Activity Not Found.", Toast.LENGTH_SHORT)
                        .show()
                }
                true
            }

            R.id.capture_video -> {
                try {
                    captureVideoLauncher.launch(Unit)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Activity Not Found.", Toast.LENGTH_SHORT)
                        .show()
                }
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    private fun onFileSelected(uri: Uri?) {
        selectedVideoUri = uri
        if (uri != null) {
            txtSelectedVideoPath.text = uri.toString()
            btnPlaySelectedVideo.isEnabled = true
            btnStartReencoding.isEnabled = true
        } else {
            txtSelectedVideoPath.text = ""
            btnPlaySelectedVideo.isEnabled = false
            btnStartReencoding.isEnabled = false
        }
    }

    private fun shrink() {
        progress.visibility = View.VISIBLE
        outputDir.mkdirs()

        launch {
            try {
                mediaShrinkQueue.queue(selectedVideoUri!!, Uri.fromFile(outputFile))
                progress.visibility = View.GONE
                btnPlayReencodedVideo.isEnabled = true
                Toast.makeText(this@ExampleActivity, "Success!", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to shrink media.")
                progress.visibility = View.GONE
                btnPlayReencodedVideo.isEnabled = true
                outputFile.delete()
                var errorMessage = t.message
                if (errorMessage.isNullOrEmpty()) errorMessage = "Failed to sh≤rink."
                Toast.makeText(this@ExampleActivity, errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playVideo(uri: Uri?) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "video/*")
        if (ContentResolver.SCHEME_CONTENT == uri!!.scheme) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    // ===== OnClickListener ===== //
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_select_video -> v.showContextMenu()
            R.id.btn_start_reencoding -> shrink()
            R.id.btn_play_selected_video -> playVideo(selectedVideoUri)
            R.id.btn_play_reencoded_video -> playVideo(FileProvider.getUriForFile(this,
                    "com.lisb.android.mediashrink.example.fileprovider", outputFile))
        }
    }

    companion object {
        private const val TAG = "ExampleActivity"
        private const val MAX_WIDTH = 384
        private const val VIDEO_BITRATE = 500 * 1024
        private const val AUDIO_BITRATE = 128 * 1024
        private const val DURATION_LIMIT: Long = 90
        private const val EXPORT_DIR = "exports"
        private const val EXPORT_FILE = "video.mp4"
        private const val SAVED_SELECTED_URI = "selected_uri"
    }
}