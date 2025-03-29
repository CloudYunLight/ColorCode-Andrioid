package com.cloudair754.sendvideos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cloudair754.sendvideos.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoHandler: VideoHandler
    private lateinit var networkStatusChecker: NetworkStatusChecker

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            videoHandler.startCamera()
        } else {
            // 处理权限被拒绝的情况
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化网络状态检查器

        networkStatusChecker = NetworkStatusChecker(this) { drawableRes, statusText ->
            runOnUiThread {
                val drawable: Drawable? = ContextCompat.getDrawable(this, drawableRes)
                binding.networkStatusIndicator.background = drawable
                binding.networkStatusText.text = statusText
            }
        }

        videoHandler = VideoHandler(this, binding.previewView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            videoHandler.startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.recordButton.setOnClickListener {
            videoHandler.startRecording()
        }

        binding.stopButton.setOnClickListener {
            videoHandler.stopRecording()
        }

        // 在MainActivity类中添加这些变量
        val configLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
    }

    override fun onResume() {
        super.onResume()
        networkStatusChecker.startChecking()
    }

    override fun onPause() {
        super.onPause()
        networkStatusChecker.stopChecking()
    }



    override fun onDestroy() {
        super.onDestroy()
        videoHandler.cleanup()
    }


}