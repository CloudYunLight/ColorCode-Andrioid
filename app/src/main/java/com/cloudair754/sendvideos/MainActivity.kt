package com.cloudair754.sendvideos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cloudair754.sendvideos.databinding.ActivityMainBinding

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

        // 在onCreate方法中添加配置按钮
        binding.configButton.setOnClickListener {
            val intent = Intent(this, ConfigActivity::class.java)
            configLauncher.launch(intent)
        }

        // 在onCreate方法中
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