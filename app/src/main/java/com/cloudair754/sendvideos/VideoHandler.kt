package com.cloudair754.sendvideos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoHandler(private val context: Context, private val previewView: androidx.camera.view.PreviewView) {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var outputFile: File? = null

    companion object {
        private const val TAG = "VideoHandler" // Log tag for debugging
    }

    /**
     * 初始化并启动相机预览。
     */
    fun startCamera() {
        Log.d(TAG, "Starting camera...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 设置预览
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 设置视频录制
            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                // 解绑所有用例并重新绑定
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
                Log.d(TAG, "Camera successfully started.")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))

        // 初始化单线程执行器
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "Camera executor initialized.")
    }

    /**
     * 开始录制视频。
     */
    fun startRecording() {
        Log.d(TAG, "Starting video recording...")
        // 实现录制逻辑
    }

    /**
     * 停止录制视频。
     */
    fun stopRecording() {
        Log.d(TAG, "Stopping video recording...")
        // 实现停止录制逻辑
    }

    /**
     * 上传视频文件。
     * @param callback 上传结果的回调函数。
     */
    fun uploadVideo(callback: (Boolean) -> Unit) {
        Log.d(TAG, "Attempting to upload video...")
        outputFile?.let {
            VideoUploader.uploadVideo(it, callback)
        } ?: run {
            Log.e(TAG, "No output file found for upload.")
            callback(false)
        }
    }

    /**
     * 清理资源，关闭执行器。
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up resources...")
        cameraExecutor.shutdown()
    }
}