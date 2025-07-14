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
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * 视频录制控制器
 * 功能：
 * 1. 管理视频录制生命周期（开始/停止）
 * 2. 处理视频文件存储
 * 3. 将视频添加到系统媒体库
 * 4. 提供录制结果访问接口
 */

class VideoRecorder(
    private val context: Context,
    private val videoCapture: VideoCapture<Recorder>
) {

    private var recording: Recording? = null
    private var outputFile: File? = null
    private var outputUri: Uri? = null

    companion object {
        private const val TAG = "VideoRecorder"
    }

    /**
     * 开始录制视频
     * 流程：
     * 1. 检查是否已有录制会话
     * 2. 创建输出文件
     * 3. 配置输出选项
     * 4. 启动录制并监听事件
     */
    fun startRecording() {
        // 检查是否正在录制
        if (recording != null) {
            Log.e(TAG, "A recording is already in progress.")
            Toast.makeText(context, "Recording is already in progress", Toast.LENGTH_SHORT).show()
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

        // 启动录制并监听事件
        /*
         *VideoCapture 是 CameraX 提供的用于视频捕获的用例：
         *
         */

        recording = videoCapture.output
            .prepareRecording(context, fileOutputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    // 录制开始
                    //VideoRecordEvent 是一个系统函数
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Recording started. File: ${outputFile?.absolutePath}")
                        // 提示用户文件存储位置
                        showToast("The video has started recording 【startRecording】")
                    }

                    // 录制结束事件
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            // 录制失败处理
                            Log.e(TAG, "Recording failed: ${event.error}")
                            outputFile?.delete() // 删除失败的文件
                        } else {
                            // 录制成功处理
                            Log.d(TAG, "Record Success,File Path:${outputFile?.absolutePath}")
                            // 其实在这里以上，就已经因为VideoRecordEvent保存了一个视频了


                            // 调用addVideoToMediaStore 会增加一个视频到相册、但是会重复存储……
                            outputUri = addVideoToMediaStore(outputFile!!)
                            // 屏蔽这个函数，只会让视频不重复+相册；上传行为不受影响

                            if (outputUri != null) {
                                //showToast("视频已保存到公共目录：${outputUri}")
                                showToast("The video has been saved to the public directory")

                            }
                        }
                        recording = null    // 重置录制会话
                    }
                }
            }
    }

    /**
     * 停止录制视频
     * 1. 停止当前录制会话
     * 2. 触发视频上传
     */
    fun stopRecording() {
        recording?.stop()   // 停止录制
        // 停止录制，并确保文件写入完成
        // 这个recoding是在startRecoding中生成的；是videoCapture类的对象，对象中保留了视频路径

        recording = null    // 重置录制会话
        Log.d(TAG, "Recording has stopped[By btn clicked]")

        // 上传视频文件
        // 此处用的文件是recoding直接产生的内容，而且成功上传后会删除掉

        outputFile?.let { file ->
            // 检查是否启用帧生成
            val sharedPref = context.getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE)
            val isRemoteUploadMode = sharedPref.getBoolean("remote_upload", false)

            if (isRemoteUploadMode) {
                // 远程上传模式
                VideoUploader.uploadVideo(context, file) { success ->
                    if (success) {
                        Log.d(TAG, "Video Upload Succeed")
                    } else {
                        Log.e(TAG, "Video Upload Failed")
                    }
                }
            } else {
                // 本地拆帧模式
                FFmpegFrameExtractor.extractFramesToGallery(
                    context,
                    file
                ) { frameSuccess, outputDir ->
                    if (frameSuccess) {
                        Log.d(TAG, "Frame Succeed")
                    } else {
                        Log.e(TAG, "Frame Failed")
                    }
                }

            }
        }

    }

    /**
     * 创建视频文件。
     * @return 返回创建的 File 对象，如果失败则返回 null。
     */

    private fun createVideoFile(): File? {
        // 获取公共视频目录[./0/MOVIES]
        val storageDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)

        // 创建ColorCode子目录
        val yzrDir = File(storageDir, "ColorCode")
        if (!yzrDir.exists()) {
            if (!yzrDir.mkdirs()) {
                Log.e(TAG, "Failed to create ColorCode directory")
                showToast("Unable to create ColorCode directory")
                return null
            }
        }

        // 生成带时间戳的文件名
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "[yzr]CQR_${timeStamp}.mp4"

        return File(yzrDir, fileName).also {
            Log.d(TAG, "Video file created: ${it.absolutePath}")
            showToast("The video file has been created and stored in:${it.absolutePath}")
        }
    }

    /**
     * 将视频文件添加到 MediaStore，使其在系统的相册中可见。
     * @param file 要添加的视频文件。
     * @return 返回文件的 Uri，如果失败则返回 null。
     */

    private fun addVideoToMediaStore(file: File): Uri? {
        if (!file.exists()) {
            Log.e(TAG, "Source file does not exist: ${file.path}")
            return null
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
            put(MediaStore.Video.Media.SIZE, file.length())
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        return try {
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )?.also { uri ->
                context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { fos ->
                        file.inputStream().use { it.copyTo(fos) }
                    }
                }
                Log.d(TAG, "Video successfully saved to MediaStore: $uri")
            } ?: run {
                Log.e(TAG, "Failed to create MediaStore entry")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "IO error saving video to MediaStore", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error saving video to MediaStore", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error saving video to MediaStore", e)
            null
        }
    }


    /**
     * 显示 Toast 提示。
     * @param message 要显示的消息。
     */
    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }


}