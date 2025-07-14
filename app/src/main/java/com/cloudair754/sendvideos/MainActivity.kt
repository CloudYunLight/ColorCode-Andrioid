package com.cloudair754.sendvideos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cloudair754.sendvideos.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoHandler: VideoHandler
    private lateinit var networkStatusChecker: NetworkStatusChecker

    // 权限请求启动器，用于处理相机权限请求结果
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            videoHandler.startCamera()
        } else {

            // 处理权限被拒绝的情况（可添加更友好的提示）
            Toast.makeText(this, "需要相机（？只有这个吗）权限才能使用此功能", Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化视图绑定
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化网络状态检查器

        networkStatusChecker = NetworkStatusChecker(this) { drawableRes, statusText ->
            // 在主线程更新UI
            runOnUiThread {
                val drawable: Drawable? = ContextCompat.getDrawable(this, drawableRes)
                binding.networkStatusIndicator.background = drawable
                binding.networkStatusText.text = statusText
            }
        }

        // 设置上传器的网络检查器
        VideoUploader.setNetworkChecker(networkStatusChecker)

        // 初始化视频处理器后设置监听器
        videoHandler = VideoHandler(this, binding.previewView).apply {
            setRecordingStateListener(object : VideoHandler.RecordingStateListener {
                override fun onRecordingStarted() {
                    runOnUiThread {
                        binding.recordButton.isEnabled = false
                        binding.stopButton.isEnabled = true
                    }
                }

                override fun onRecordingStopped() {
                    runOnUiThread {
                        binding.recordButton.isEnabled = true
                        binding.stopButton.isEnabled = false
                    }
                }
            })
        }
        // 初始状态设置
        binding.recordButton.isEnabled = true
        binding.stopButton.isEnabled = false



        // 检查相机权限
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // 已有权限，直接启动相机
            videoHandler.startCamera()
        } else {
            // 请求相机权限
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.recordButton.setOnClickListener {
            // 启动录制~
            videoHandler.startRecording()
        }

        binding.stopButton.setOnClickListener {
            videoHandler.stopRecording()
        }

        // 配置Activity结果处理启动器
        val configLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    // URL可能已更新，可以在这里处理
                    Toast.makeText(this, "URL updated", Toast.LENGTH_SHORT).show()
                }
            }

        // 跳转到配置页面
        binding.configButton.setOnClickListener {
            val intent = Intent(this, ConfigActivity::class.java)
            configLauncher.launch(intent)
        }

        // 调节缩放
        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            @SuppressLint("SetTextI18n")
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return

                // 使用增强版缩放控制
                val zoomValue = videoHandler.setEnhancedZoom(progress)

                // 更新显示
                binding.zoomValueText.text = "%.1fx".format(zoomValue)

            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 触摸开始时添加视觉反馈
                binding.zoomControlContainer.background =
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_zoom_active)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 触摸结束时恢复背景
                binding.zoomControlContainer.background =
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_zoom_normal)
            }
        })

        // 初始化时设置合理的范围
        val (minZoom, maxZoom) = videoHandler.getZoomRange()
        binding.zoomSeekBar.max = 200
        binding.zoomSeekBar.progress = 0  // 默认居左



        // Set up the select video button
        findViewById<Button>(R.id.selectVideoButton).setOnClickListener {
            selectVideoFromGallery()
        }


    }


    private fun selectVideoFromGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, REQUEST_CODE_SELECT_VIDEO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SELECT_VIDEO && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                // Get the file from URI and process it
                handleSelectedVideo(uri)
            }
        }
    }

    private fun handleSelectedVideo(uri: Uri) {
        try {
            // Create a temporary file to store the selected video
            val inputStream = contentResolver.openInputStream(uri)
            val fileName = getFileName(uri) ?: "selected_video_${System.currentTimeMillis()}.mp4"
            val tempFile = File(cacheDir, fileName)

            FileOutputStream(tempFile).use { output ->
                inputStream?.copyTo(output)
            }

            // Now process the file similar to how you handle recorded videos
            val sharedPref = getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE)
            val isRemoteUploadMode = sharedPref.getBoolean("remote_upload", false)

            if (isRemoteUploadMode) {
                // Remote upload mode
                VideoUploader.uploadVideo(this, tempFile) { success ->
                    if (success) {
                        Log.d(TAG, "Selected video upload succeeded")
                        // Delete the temp file after successful upload
                        tempFile.delete()
                    } else {
                        Log.e(TAG, "Selected video upload failed")
                    }
                }
            } else {
                // Local frame extraction mode
                FFmpegFrameExtractor.extractFramesToGallery(this, tempFile) { frameSuccess, outputDir ->
                    if (frameSuccess) {
                        Log.d(TAG, "Frame extraction from selected video succeeded")
                        // Delete the temp file after successful processing
                        tempFile.delete()
                    } else {
                        Log.e(TAG, "Frame extraction from selected video failed")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling selected video", e)
            Toast.makeText(this, "Error processing selected video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        }
        return name
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_SELECT_VIDEO = 1001
    }

    override fun onResume() {
        super.onResume()
        // Activity恢复时开始检查网络状态
        networkStatusChecker.startChecking()
    }

    override fun onPause() {
        super.onPause()
        // Activity暂停时停止检查网络状态
        networkStatusChecker.stopChecking()
    }


    override fun onDestroy() {
        super.onDestroy()
        // Activity销毁时清理视频处理器资源
        videoHandler.cleanup()
    }


}