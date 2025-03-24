package com.cloudair754.sendvideos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cloudair754.sendvideos.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoHandler: VideoHandler

    companion object {
        private const val TAG = "MainActivity" // Log tag for debugging
    }

    // 权限请求结果处理
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Camera permission granted. Starting camera...")
            videoHandler.startCamera()
        } else {
            Log.e(TAG, "Camera permission denied.")
            // 处理权限被拒绝的情况
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called.")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 VideoHandler
        videoHandler = VideoHandler(this, binding.previewView)
        Log.d(TAG, "VideoHandler initialized.")

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission already granted. Starting camera...")
            videoHandler.startCamera()
        } else {
            Log.d(TAG, "Requesting camera permission...")
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // 绑定按钮点击事件
        binding.recordButton.setOnClickListener {
            Log.d(TAG, "Record button clicked.")
            videoHandler.startRecording()
        }

        binding.stopButton.setOnClickListener {
            Log.d(TAG, "Stop button clicked.")
            videoHandler.stopRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called. Cleaning up resources...")
        videoHandler.cleanup()
    }
}