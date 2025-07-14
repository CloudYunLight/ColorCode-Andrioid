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
import androidx.lifecycle.lifecycleScope
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
        val defaultUrl = "http://IP:5000" // 设置你的默认URL

        // 从共享偏好设置中读取已保存的URL，如果没有则使用默认URL
        val savedUrl = sharedPref.getString("upload_url", defaultUrl)
        binding.urlEditText.setText(savedUrl)


        // 读取当前模式（默认本地拆帧）
        val isRemoteUploadMode = sharedPref.getBoolean("remote_upload", false)
        if (isRemoteUploadMode) {
            binding.remoteUploadRadio.isChecked = true
        } else {
            binding.localFramesRadio.isChecked = true
        }



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
                Log.e(TAG, "Cleaning frame image failed", e)
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
            Log.e(TAG, "Failed to delete from MediaStore", e)
        }
    }


    /**
     * 清理MOVIES/ColorCode目录下的所有文件
     *
     */
    private fun cleanColorCodeDirectory() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val colorCodeDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "ColorCode"
                    )

                    if (!colorCodeDir.exists() || !colorCodeDir.isDirectory) return@withContext 0 to R.string.no_files

                    val files = colorCodeDir.listFiles() ?: return@withContext 0 to R.string.no_files
                    val deletedCount = files.count { it.delete() }

                    if (deletedCount > 0) deletedCount to R.string.clean_success
                    else 0 to R.string.no_files

                } catch (e: SecurityException) {
                    Log.e(TAG, "权限不足", e)
                    0 to R.string.clean_permission_denied
                } catch (e: Exception) {
                    Log.e(TAG, "Cleaning failed", e)
                    0 to R.string.clean_failed
                }
            }

            val (count, resId) = result
            val message = if (resId == R.string.clean_success) {
                getString(resId, count)
            } else {
                getString(resId)
            }
            Toast.makeText(this@ConfigActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 伴生对象，包含日志标签
    companion object {
        private const val TAG = "ConfigActivity"
    }

    // 保存设置并返回
    private fun saveSettingsAndReturn() {
        val url = binding.urlEditText.text.toString().trim()
        val isRemoteUploadMode = binding.remoteUploadRadio.isChecked

        when {
            url.isEmpty() -> binding.urlEditText.error = "URL cannot be empty"
            !url.startsWith("http://") && !url.startsWith("https://") ->
                binding.urlEditText.error = "URL must start with http:// or https://"

            else -> {
                // 保存所有设置
                sharedPref.edit()
                    .putString("Base_url", url)
                    .putBoolean("remote_upload", isRemoteUploadMode)
                    .putBoolean("generate_frames", !isRemoteUploadMode) // 本地拆帧与远程上传互斥
                    .apply()

                setResult(RESULT_OK)
                finish()
            }
        }
    }

}