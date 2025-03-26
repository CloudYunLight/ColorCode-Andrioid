package com.cloudair754.sendvideos

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoRecorder(private val context: Context, private val videoCapture: VideoCapture<Recorder>) {

    private var recording: Recording? = null
    private var outputFile: File? = null
    private var outputUri: Uri? = null

    companion object {
        private const val TAG = "VideoRecorder"
    }

    /**
     * 开始录制视频。
     */
    fun startRecording() {
        if (recording != null) {
            Log.e(TAG, "Recording is already in progress.")
            return
        }

        // 创建输出文件
        outputFile = createVideoFile()
        if (outputFile == null) {
            Log.e(TAG, "Failed to create video file.")
            return
        }

        // 设置文件输出选项
        val fileOutputOptions = FileOutputOptions.Builder(outputFile!!).build()

        // 开始录制
        recording = videoCapture.output
            .prepareRecording(context, fileOutputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Recording started. File: ${outputFile?.absolutePath}")
                        // 提示用户文件存储位置
                        showToast("视频已开始录制，存储位置：${outputFile?.absolutePath}")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "Recording failed: ${event.error}")
                            outputFile?.delete() // 删除失败的文件
                        } else {
                            Log.d(TAG, "Recording finished. File: ${outputFile?.absolutePath}")
                            // 将文件添加到 MediaStore
                            outputUri = addVideoToMediaStore(outputFile!!)
                            if (outputUri != null) {
                                showToast("视频已保存到公共目录：${outputUri}")
                            }
                        }
                        recording = null
                    }
                }
            }
    }

    /**
     * 停止录制视频。
     */
    fun stopRecording() {
        recording?.stop()
        recording = null
        Log.d(TAG, "Recording stopped.")

        // 上传视频文件
        outputFile?.let { file ->
            VideoUploader.uploadVideo(context,file) { success ->
                if (success) {
                    Log.d(TAG, "Video uploaded successfully.")
                } else {
                    Log.e(TAG, "Video upload failed.")
                }
            }
        }
    }

    /**
     * 创建视频文件。
     * @return 返回创建的 File 对象，如果失败则返回 null。
     */
    private fun createVideoFile(): File? {
        val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (!storageDir.exists()) {
            storageDir.mkdirs() // 如果目录不存在，则创建
        }

        // 生成文件名
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "VIDEO_${timeStamp}.mp4"

        return File(storageDir, fileName).also {
            Log.d(TAG, "Video file created: ${it.absolutePath}")
            // 提示用户文件存储位置
            showToast("视频文件已创建，存储位置：${it.absolutePath}")
        }
    }

    /**
     * 将视频文件添加到 MediaStore，使其在系统的相册中可见。
     * @param file 要添加的视频文件。
     * @return 返回文件的 Uri，如果失败则返回 null。
     */
    private fun addVideoToMediaStore(file: File): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
        }

        return try {
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                val outputStream = context.contentResolver.openOutputStream(uri)
                outputStream?.use { os ->
                    file.inputStream().use { it.copyTo(os) }
                }
                val videoURi = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                Log.i(TAG,"Root Path:$videoURi")
                Log.d(TAG, "Video added to MediaStore: $uri")
            } else {
                Log.e(TAG, "Failed to insert video into MediaStore")
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Error adding video to MediaStore", e)
            null
        }
    }

    /**
     * 获取录制的视频文件。
     * @return 返回录制的视频文件，如果未录制则返回 null。
     */
    fun getOutputFile(): File? {
        return outputFile
    }

    /**
     * 获取录制的视频文件的 Uri。
     * @return 返回视频文件的 Uri，如果未录制则返回 null。
     */
    fun getOutputUri(): Uri? {
        return outputUri
    }

    /**
     * 显示 Toast 提示。
     * @param message 要显示的消息。
     */
    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}