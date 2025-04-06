// ConfigActivity.kt
package com.cloudair754.sendvideos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cloudair754.sendvideos.databinding.ActivityConfigBinding
import java.io.File
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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


        // 加载帧生成设置，默认为true
        val generateFrames = sharedPref.getBoolean("generate_frames", true)
        binding.frameGenerationSwitch.isChecked = generateFrames


        binding.saveButton.setOnClickListener {
            saveSettingsAndReturn()
        }

        binding.backButton.setOnClickListener {
            finish()
        }

        binding.cleanButton.setOnClickListener {
            cleanColorCodeDirectory()
        }
        // 在onCreate方法中添加按钮点击监听
        binding.cleanFramesButton.setOnClickListener {
            showCleanFramesConfirmation()
        }


    }

    // 显示清理帧图片确认对话框
    private fun showCleanFramesConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(Html.fromHtml(getString((R.string.confirm_clean_frames_title))))
            .setMessage(Html.fromHtml(getString((R.string.confirm_clean_frames_message))))
            //.setTitle(getString(R.string.confirm_clean_frames))
            .setPositiveButton(Html.fromHtml(getString((R.string.confirm_delete_button)))) { _, _ ->
                cleanFramesAndMediaStore()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    private fun cleanFramesAndMediaStore() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    cleanFramesAndMediaStoreInternal()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ConfigActivity,
                        result.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理帧图片失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ConfigActivity,
                        R.string.clean_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private suspend fun cleanFramesAndMediaStoreInternal(): CleanResult {
        val picturesDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val frameDirs = picturesDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("VideoFrames_[yzr]CQR")
        }

        val albumNames = mutableListOf<String>()
        var deletedCount = 0

        frameDirs?.forEach { dir ->
            val files = dir.listFiles()
            if (files != null) {
                deletedCount += files.size
            }
            dir.deleteRecursively()
            albumNames.add(dir.name)
        }

        if (albumNames.isNotEmpty()) {
            deleteFromMediaStore(albumNames)
        }

        return CleanResult(
            success = true,
            message = if (deletedCount > 0) {
                getString(R.string.frames_clean_success, deletedCount)
            } else {
                getString(R.string.no_frames_found)
            }
        )
    }

    data class CleanResult(val success: Boolean, val message: String)

    private fun deleteFromMediaStore(albumNames: List<String>) {
        val resolver = contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection =
            "${MediaStore.Images.Media.RELATIVE_PATH} in (${albumNames.joinToString(",") { "?" }})"
        val selectionArgs = albumNames.toTypedArray()

        try {
            resolver.delete(uri, selection, selectionArgs)
        } catch (e: Exception) {
            Log.e(TAG, "从MediaStore删除失败", e)
        }
    }


    /**
     * 清理MOVIES/ColorCode目录下的所有文件
     *
     */
    // TODO 删除临时视频，可加速
    private fun cleanColorCodeDirectory() {
        try {
            // 获取ColorCode目录
            val colorCodeDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ColorCode"
            )

            // 检查目录是否存在
            if (!colorCodeDir.exists() || !colorCodeDir.isDirectory) {
                Toast.makeText(this, getString(R.string.no_files), Toast.LENGTH_SHORT).show()
                return
            }

            // 删除目录下所有文件
            val files = colorCodeDir.listFiles()
            if (files.isNullOrEmpty()) {
                Toast.makeText(this, getString(R.string.no_files), Toast.LENGTH_SHORT).show()
                return
            }

            var deletedCount = 0
            files.forEach { file ->
                if (file.delete()) {
                    deletedCount++
                }
            }

            // 显示清理结果
            val message = if (deletedCount > 0) {
                getString(R.string.clean_success, deletedCount)
            } else {
                getString(R.string.no_files)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "清理文件失败", e)
            Toast.makeText(this, R.string.clean_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // 伴生对象，包含日志标签
    companion object {
        private const val TAG = "ConfigActivity"
    }

    // 保存设置并返回
    private fun saveSettingsAndReturn() {
        val url = binding.urlEditText.text.toString().trim()
        val generateFrames = binding.frameGenerationSwitch.isChecked

        when {
            url.isEmpty() -> binding.urlEditText.error = "URL cannot be empty"
            !url.startsWith("http://") && !url.startsWith("https://") ->
                binding.urlEditText.error = "URL must start with http:// or https://"

            else -> {
                // 保存所有设置
                sharedPref.edit()
                    .putString("upload_url", url)
                    .putBoolean("generate_frames", generateFrames)
                    .apply()

                setResult(RESULT_OK)
                finish()
            }
        }
    }

}