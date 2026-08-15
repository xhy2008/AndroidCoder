package com.coderagent.android

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coderagent.android.databinding.ActivityMediaViewerBinding
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 媒体查看：容器内图片/音视频先经 bind mount 导出到应用缓存，再显示/播放。
 */
class MediaViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private var path = ""
    private var type = TYPE_UNKNOWN
    private var player: MediaPlayer? = null
    private val ticker = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        path = intent.getStringExtra("path") ?: ""
        binding.toolbar.subtitle = path
        type = typeOf(path)

        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.audioSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        exportAndShow()
    }

    private fun exportAndShow() {
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { exportToCache() }
            if (local == null) {
                binding.errText.visibility = View.VISIBLE
                return@launch
            }
            when (type) {
                TYPE_IMAGE -> showImage(local)
                TYPE_VIDEO -> showVideo(local)
                TYPE_AUDIO -> showAudio(local)
                else -> binding.errText.visibility = View.VISIBLE
            }
        }
    }

    /** 容器文件 -> tmp(bind mount) -> 应用 cacheDir，返回本地 File（null 失败） */
    private fun exportToCache(): File? {
        return try {
            val tmp = ContainerRuntime.tmpDir(this)
            val tmpName = "mv-${UUID.randomUUID()}"
            val r = ContainerRuntime.exec(
                this,
                "cp ${ToolRegistry.shq(path)} /hosttmp/$tmpName && echo OK || echo FAIL",
                "/root", timeoutSec = 120
            )
            if (!r.output.contains("OK")) return null
            val src = File(tmp, tmpName)
            val dst = File(cacheDir, tmpName + "." + path.substringAfterLast('.', "bin"))
            src.copyTo(dst, overwrite = true)
            src.delete()
            dst
        } catch (e: Exception) {
            null
        }
    }

    private fun showImage(f: File) {
        binding.imageView.visibility = View.VISIBLE
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) sample *= 2
            val full = BitmapFactory.Options().apply { inSampleSize = sample }
            binding.imageView.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, full))
        } catch (e: Exception) {
            binding.errText.visibility = View.VISIBLE
        }
    }

    private fun showVideo(f: File) {
        binding.videoView.visibility = View.VISIBLE
        val mc = MediaController(this)
        binding.videoView.setMediaController(mc)
        binding.videoView.setVideoURI(Uri.fromFile(f))
        binding.videoView.setOnPreparedListener { it.start() }
        binding.videoView.setOnErrorListener { _, _, _ ->
            binding.errText.visibility = View.VISIBLE
            true
        }
        binding.videoView.setOnInfoListener { _, what, _ -> false }
        binding.videoView.requestFocus()
        binding.videoView.start()
    }

    private fun showAudio(f: File) {
        binding.audioPanel.visibility = View.VISIBLE
        binding.audioStatus.text = f.name
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            setDataSource(f.absolutePath)
            setOnPreparedListener { mp ->
                binding.audioSeek.max = mp.duration
                mp.start()
                binding.btnPlay.text = getString(R.string.media_pause)
                ticker.post(progressRunnable)
            }
            setOnErrorListener { _, _, _ ->
                binding.errText.visibility = View.VISIBLE
                true
            }
            prepareAsync()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = player ?: return
            binding.audioSeek.progress = mp.currentPosition
            ticker.postDelayed(this, 500)
        }
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            binding.btnPlay.text = getString(R.string.media_play)
        } else {
            mp.start()
            binding.btnPlay.text = getString(R.string.media_pause)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish(); return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        ticker.removeCallbacks(progressRunnable)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TYPE_UNKNOWN = 0
        private const val TYPE_IMAGE = 1
        private const val TYPE_VIDEO = 2
        private const val TYPE_AUDIO = 3

        private fun typeOf(path: String): Int {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "png", "jpg", "jpeg", "gif", "bmp", "webp" -> TYPE_IMAGE
                "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "flv" -> TYPE_VIDEO
                "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus" -> TYPE_AUDIO
                else -> TYPE_UNKNOWN
            }
        }
    }
}
