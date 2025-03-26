// ConfigActivity.kt
package com.cloudair754.sendvideos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cloudair754.sendvideos.databinding.ActivityConfigBinding

class ConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigBinding
    private val sharedPref by lazy { getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 加载保存的URL或使用默认值
        val defaultUrl = "http://10.195.152.71:5000/upload" // 设置你的默认URL
        val savedUrl = sharedPref.getString("upload_url", defaultUrl)
        binding.urlEditText.setText(savedUrl)

        binding.saveButton.setOnClickListener {
            saveUrlAndReturn()
        }

        binding.backButton.setOnClickListener {
            finish()
        }
    }


    private fun saveUrlAndReturn() {
        val url = binding.urlEditText.text.toString().trim()
        when {
            url.isEmpty() -> binding.urlEditText.error = "URL cannot be empty"
            !url.startsWith("http://") && !url.startsWith("https://") ->
                binding.urlEditText.error = "URL must start with http:// or https://"
            else -> {
                sharedPref.edit().putString("upload_url", url).apply()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

}