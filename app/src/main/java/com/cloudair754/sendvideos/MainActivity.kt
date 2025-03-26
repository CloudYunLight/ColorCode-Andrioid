package com.cloudair754.sendvideos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cloudair754.sendvideos.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoHandler: VideoHandler

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

    override fun onDestroy() {
        super.onDestroy()
        videoHandler.cleanup()
    }


}