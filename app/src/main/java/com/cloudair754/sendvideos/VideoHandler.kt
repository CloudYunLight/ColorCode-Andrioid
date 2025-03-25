package com.cloudair754.sendvideos

import android.content.Context
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoHandler(private val context: Context, private val previewView: PreviewView) {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var videoRecorder: VideoRecorder

    companion object {
        private const val TAG = "VideoHandler"
        private val TARGET_ASPECT_RATIO = AspectRatio.RATIO_16_9 // 4:3 比例
    }

    /**
     * 初始化并启动相机预览。
     */
    fun startCamera() {
        Log.d(TAG, "Starting camera with 4:3 aspect ratio...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 设置预览
            val preview = Preview.Builder()
                .setTargetAspectRatio(TARGET_ASPECT_RATIO) // 设置预览比例为 16:9
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // 设置视频录制
            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)

            // 初始化 VideoRecorder
            videoRecorder = VideoRecorder(context, videoCapture)

            try {
                // 解绑所有用例并重新绑定
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
                Log.d(TAG, "Camera successfully started with 4:3 aspect ratio.")
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
        videoRecorder.startRecording()
    }

    /**
     * 停止录制视频。
     */
    fun stopRecording() {
        videoRecorder.stopRecording()
    }

    /**
     * 获取录制的视频文件。
     * @return 返回录制的视频文件，如果未录制则返回 null。
     */
    fun getOutputFile(): File? {
        return videoRecorder.getOutputFile()
    }

    /**
     * 清理资源，关闭执行器。
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up resources...")
        cameraExecutor.shutdown()
    }
}