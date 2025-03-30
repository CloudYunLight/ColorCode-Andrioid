// ConfigActivity.kt
package com.cloudair754.sendvideos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cloudair754.sendvideos.databinding.ActivityConfigBinding

class ConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigBinding

    // 共享偏好设置，用于持久化存储配置信息
    private val sharedPref by lazy { getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化视图绑定
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 加载保存的URL或使用默认值
        val defaultUrl = "http://IP:5000/upload" // 设置你的默认URL

        // 从共享偏好设置中读取已保存的URL，如果没有则使用默认URL
        val savedUrl = sharedPref.getString("upload_url", defaultUrl)
        binding.urlEditText.setText(savedUrl)

        binding.saveButton.setOnClickListener {
            saveUrlAndReturn()
        }

        binding.backButton.setOnClickListener {
            finish()
        }
    }


    /**
     * 保存URL并返回
     * 包含输入验证逻辑：
     * 1. URL不能为空
     * 2. URL必须以http://或https://开头
     */
    private fun saveUrlAndReturn() {
        val url = binding.urlEditText.text.toString().trim()
        when {
            url.isEmpty() -> binding.urlEditText.error = "URL cannot be empty"
            // 验证URL协议头
            !url.startsWith("http://") && !url.startsWith("https://") ->
                binding.urlEditText.error = "URL must start with http:// or https://"
            else -> {
                // 保存URL到共享偏好设置
                sharedPref.edit().putString("upload_url", url).apply()
                // 设置操作结果为成功
                setResult(RESULT_OK)
                finish()
            }
        }
    }

}