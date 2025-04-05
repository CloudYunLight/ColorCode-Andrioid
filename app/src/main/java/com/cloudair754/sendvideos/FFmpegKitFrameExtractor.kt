package com.cloudair754.sendvideos

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
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
    * 从视频文件获取日期信息
    */
    private fun getVideoDate(videoFile: File): Date {
        // 尝试从文件名解析日期（根据您的命名格式）
        val fileName = videoFile.name
        val regex = Regex("""\d{8}_\d{6}""") // 匹配yyyyMMdd_HHmmss格式
        val dateStr = regex.find(fileName)?.value ?: ""

        return if (dateStr.isNotEmpty()) {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(dateStr) ?: Date(videoFile.lastModified())
        } else {
            Date(videoFile.lastModified())
        }
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

        return if (fontPath.isNotEmpty()) {
            "-i ${videoFile.absolutePath} " +
                    "-vf \"fps=30,drawtext=fontfile=$fontPath:text='Frame %{frame_num}':x=10:y=h-th-10:fontsize=24:fontcolor=white\" " +
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
                Log.d(TAG, log.message)
            }, { statistics ->
                // 可以在这里处理进度更新
                val progress = statistics.videoFrameNumber.toFloat() / FRAME_RATE
                Log.d(TAG, "Processing progress: $progress")
            })
        }.start()
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
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumName")
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