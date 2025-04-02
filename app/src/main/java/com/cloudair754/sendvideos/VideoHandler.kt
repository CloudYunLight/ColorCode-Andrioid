package com.cloudair754.sendvideos

import android.content.Context
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.lang.Math.pow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * 视频处理控制器
 * 功能：
 * 1. 管理相机预览和视频录制生命周期
 * 2. 控制视频录制开始/停止
 * 3. 提供录制的视频文件
 */
class VideoHandler(private val context: Context, private val previewView: PreviewView) {

    // 添加录制状态监听器接口
    interface RecordingStateListener {
        fun onRecordingStarted()
        fun onRecordingStopped()
    }

    private var recordingStateListener: RecordingStateListener? = null

    // 设置监听器的方法
    fun setRecordingStateListener(listener: RecordingStateListener) {
        this.recordingStateListener = listener
    }

    // 相机操作执行器（单线程）
    private lateinit var cameraExecutor: ExecutorService

    // 视频捕获组件
    private lateinit var videoCapture: VideoCapture<Recorder>

    // 视频录制控制器
    private lateinit var videoRecorder: VideoRecorder

    // 添加成员变量
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null


    companion object {
        private const val TAG = "VideoHandler"
        private val TARGET_ASPECT_RATIO = AspectRatio.RATIO_16_9 // 16:9 比例
    }

    /**
     * 初始化并启动相机预览
     * 流程：
     * 1. 获取相机提供者
     * 2. 配置预览和视频录制
     * 3. 绑定到生命周期
     */
    fun startCamera() {

        Log.d(TAG, "Start Camera,Use 16:9")
        // 获取相机提供者Future
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)


        // 添加监听器，在相机准备就绪时执行
        cameraProviderFuture.addListener({
            // 获取相机提供者实例
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 设置预览
            val preview = Preview.Builder()
                .setTargetAspectRatio(TARGET_ASPECT_RATIO) // 设置预览比例为 16:9
                .build()
                .also {
                    // 将预览连接到PreviewView的表面提供者
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // 设置视频录制
            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)

            // 初始化 VideoRecorder
            videoRecorder = VideoRecorder(context, videoCapture)

            try {
                cameraProvider.unbindAll()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val camera = cameraProvider.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )

                // 获取相机控制对象
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                Log.d(TAG, "Camera control initialized")

                // 检查缩放支持
                if ((cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f ) <= 1f) {
                    Log.w(TAG, "This device doesn't support zoom")
                }

            } catch (exc: Exception) {
                Log.e(TAG, "用例绑定失败", exc)
            }
        }, ContextCompat.getMainExecutor(context))// 在主线程执行

        // 初始化单线程执行器
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "Camera executor initialized.")
    }


    /**
     * 获取相机支持的缩放范围
     */
    fun getZoomRange(): Pair<Float, Float> {
        return cameraInfo?.zoomState?.value?.let {
            it.minZoomRatio to it.maxZoomRatio
        } ?: (1f to 1f)
    }


    /**
     * 设置相机缩放级别
     * @param zoomValue 缩放值 (0.5-2.0)
     */
    fun setZoom(zoomValue: Float) {
        cameraInfo?.let { info ->
            cameraControl?.let { control ->
                val clampedZoom = zoomValue.coerceIn(
                    info.zoomState.value?.minZoomRatio ?: 1f,
                    info.zoomState.value?.maxZoomRatio ?: 1f
                )
                control.setZoomRatio(clampedZoom)
            }
        }
    }

    /**
     * 增强版缩放控制
     * @param progress 0-200的滑块进度值
     * @return 实际设置的缩放值
     */
    fun setEnhancedZoom(progress: Int): Float {
        val (minZoom, maxZoom) = getZoomRange()

        // 使用指数曲线增加小范围的灵敏度
        val normalizedProgress = progress / 200f
        val zoomValue = minZoom * pow((maxZoom / minZoom).toDouble(), normalizedProgress.toDouble()).toFloat()

        cameraControl?.setZoomRatio(zoomValue)
        return zoomValue
    }


    /**
     * 开始视频录制
     * 委托给VideoRecorder处理
     */
    fun startRecording() {
        // 将视频流权限都给videoRecoder 类去处理
        videoRecorder.startRecording()
        recordingStateListener?.onRecordingStarted()
    }

    /**
     * 停止录制视频。
     */
    fun stopRecording() {
        videoRecorder.stopRecording()
        recordingStateListener?.onRecordingStopped()
    }


    /**
     * 清理资源
     * 1. 关闭执行器
     * 2. 释放相机资源
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up resources...")
        cameraExecutor.shutdown()
    }
}