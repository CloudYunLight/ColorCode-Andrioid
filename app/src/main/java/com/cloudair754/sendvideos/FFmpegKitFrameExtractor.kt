package com.cloudair754.sendvideos

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FFmpeg视频帧提取工具
 * 功能：
 * 1. 将视频逐帧输出为图片序列
 * 2. 添加帧号水印
 * 3. 保存到相册中以拍摄日期命名的相册
 */
object FFmpegFrameExtractor {

    private const val TAG = "FFmpegFrameExtractor"
    private const val FRAME_RATE = 30 // 默认帧率，实际应从视频元数据获取
    var PercentProgressFFMPG = 0.0

    // Android系统字体路径列表
    private val systemFonts = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
        "/system/fonts/NotoSans-Regular.ttf"
    )

    /**
     * 提取视频帧并保存到相册
     * @param context 上下文
     * @param videoFile 视频文件
     * @param callback 处理完成回调
     */
    fun extractFramesToGallery(
        context: Context,
        videoFile: File,
        callback: (success: Boolean, outputDir: File?) -> Unit
    ) {

        if (!isFileReady(videoFile)) {
            Handler(Looper.getMainLooper()).postDelayed({
                extractFramesToGallery(context, videoFile, callback)
            }, 1000)
            return
        }

        // 1. 创建输出目录
        val outputDir = createOutputDirectory(context, videoFile) ?: run {
            callback(false, null)
            return
        }

        // 2. 构建FFmpeg命令
        val command = buildFFmpegCommand(videoFile, outputDir)

        // 3. 执行FFmpeg命令
        executeFFmpegCommand(context, command, outputDir, callback)

    }

    /**
     * 创建输出目录
     */
    private fun createOutputDirectory(context: Context, videoFile: File): File? {
        // 获取视频文件名（不含扩展名）
        val videoName = videoFile.nameWithoutExtension

        // 创建相册目录
        val albumDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "VideoFrames_$videoName"  // 使用视频文件名作为相册名
        )

        if (!albumDir.exists() && !albumDir.mkdirs()) {
            Log.e(TAG, "Failed to create output directory")
            return null
        }

        return albumDir
    }


    /**
     * 构建FFmpeg命令
     */
    private fun buildFFmpegCommand(videoFile: File, outputDir: File): String {
        val outputPattern = File(outputDir, "frame_%04d.png").absolutePath
        val fontPath = findAvailableFont() ?: run {
            Log.w(TAG, "No system font found, using default")
            ""
        }
        // 获取视频名称（不含扩展名）
        val videoName = videoFile.nameWithoutExtension

        return if (fontPath.isNotEmpty()) {
            // 添加带外轮廓的水印（使用shadow效果模拟描边）
            "-i ${videoFile.absolutePath} " +
                    "-vf \"fps=30," +
                    "drawtext=fontfile=$fontPath:text='$videoName':x=10:y=h-th-40:" +
                    "fontsize=24:fontcolor=white:shadowcolor=black:shadowx=2:shadowy=2," +
                    "drawtext=fontfile=$fontPath:text='Frame %{frame_num}':x=10:y=h-th-10:" +
                    "fontsize=24:fontcolor=white:shadowcolor=black:shadowx=2:shadowy=2\" " +
                    "-q:v 2 " +
                    outputPattern
        } else {
            // 不使用水印的备选方案
            "-i ${videoFile.absolutePath} " +
                    "-vf fps=30 " +
                    "-q:v 2 " +
                    outputPattern
        }
    }

    private fun findAvailableFont(): String? {
        return systemFonts.firstOrNull { File(it).exists() }
    }

    /**
     * 执行FFmpeg命令
     */
    private fun executeFFmpegCommand(
        context: Context,
        command: String, // 这里改为String类型
        outputDir: File,
        callback: (success: Boolean, outputDir: File?) -> Unit
    ) {

        var retryCount = 0
        val maxRetries = 3
        var videoDurationMs: Long = 0 // 存储视频持续时间(毫秒)
        var currentFrame: Int = 0 // 存储当前处理的帧数
        var durationStringBuffer: String? = null // 用于缓存第一条Duration消息

        fun attemptExecution() {
            // 延迟执行以确保文件完全释放
            Handler(Looper.getMainLooper()).postDelayed({
                // 异步执行FFmpeg命令
                Thread {
                    val session = FFmpegKit.executeAsync(command, { session ->
                        val returnCode = session.returnCode

                        if (ReturnCode.isSuccess(returnCode)) {
                            // 将生成的图片添加到媒体库
                            addFramesToMediaStore(context, outputDir)
                            Handler(Looper.getMainLooper()).post {
                                callback(true, outputDir)
                            }
                        } else {
                            Log.e(TAG, "FFmpeg command failed with rc=$returnCode")
                            Handler(Looper.getMainLooper()).post {
                                callback(false, null)
                            }
                        }
                    }, { log ->
                        // 解析日志信息
                        val message = log.message
                        // Log.d(TAG, message)

                        // 处理持续时间（分两条日志的情况）
                        when {
                            message.trim() == "Duration:" -> {
                                // 这是第一条Duration消息，只包含标签
                                durationStringBuffer = "Duration:"
                            }

                            durationStringBuffer == "Duration:" && message.trim()
                                .matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{2}")) -> {
                                // 这是第二条Duration消息，包含时间值
                                videoDurationMs = parseDurationToMs(message.trim())
                                //Log.d(TAG, "Parsed video duration: $videoDurationMs ms")
                                durationStringBuffer = null
                            }


                        }

                    }, { statistics ->
                        // 可以在这里处理进度更新
                        val progress = statistics.videoFrameNumber.toFloat() / FRAME_RATE
                        PercentProgressFFMPG = progress / videoDurationMs * 1000.0
                        Log.d(TAG, "Processing progress: $PercentProgressFFMPG")
                        if (PercentProgressFFMPG > 0.9) {
                            Log.i(TAG, "attemptExecution: Complete!!!")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "已经完成了照片导出~", Toast.LENGTH_LONG)
                                    .show()

                            }

                        }
                    })
                }.start()
            }, 2000) // 延迟2秒
        }

        attemptExecution()

    }


    // 更健壮的持续时间解析方法
    private fun parseDurationToMs(durationStr: String): Long {
        return try {
            val parts = durationStr.split(":", ".")
            require(parts.size >= 4) { "Invalid duration format" }

            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].toLong()
            val centiseconds = parts[3].take(2).padEnd(2, '0').toLong()

            (hours * 3600 * 1000) +
                    (minutes * 60 * 1000) +
                    (seconds * 1000) +
                    (centiseconds * 10)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing duration: $durationStr", e)
            0
        }
    }


    // 添加文件状态检查方法
    private fun isFileReady(file: File): Boolean {
        return try {
            file.renameTo(file) // 尝试重命名到自身，检查文件是否被锁定
            true
        } catch (e: Exception) {
            false
        }
    }


    /**
     * 将帧图片添加到媒体库
     */
    private fun addFramesToMediaStore(context: Context, outputDir: File) {
        outputDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".png")) {
                addImageToMediaStore(context, file, outputDir.name)
            }
        }
    }

    /**
     * 将单张图片添加到媒体库
     */
    private fun addImageToMediaStore(context: Context, file: File, albumName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            // 使用相册名作为相对路径的一部分
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$albumName"
            )
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1) // 标记为待处理
        }

        return try {
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )?.also { uri ->
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    file.inputStream().use { it.copyTo(os) }
                }
                // 删除原始文件(真好啊！！！)
                file.delete()
                // 完成写入后更新状态
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding image to MediaStore", e)
            null
        }
    }
}