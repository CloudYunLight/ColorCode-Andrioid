package com.cloudair754.sendvideos

import android.app.AlertDialog
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

    // 进度弹窗变量
    private var progressDialog: AlertDialog? = null

    // Android系统字体路径列表
    private val systemFonts = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf",          // 低版本默认
        "/system/fonts/NotoSans-Regular.ttf"    // 新版本默认
    )

    /**
     * 显示进度弹窗
     * @param context 上下文对象
     */
    private fun showProgressDialog(context: Context) {
        // 在主线程中显示弹窗
        Handler(Looper.getMainLooper()).post {
            progressDialog = AlertDialog.Builder(context)
                .setTitle("正在导出帧图片")
                .setMessage("请稍候，正在处理视频帧...")
                .setCancelable(false) // 不可手动取消
                .create()
            progressDialog?.show()
        }
    }

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
        // 检查文件是否可用（未被占用）
        if (!isFileReady(videoFile)) {
            Handler(Looper.getMainLooper()).postDelayed({
                extractFramesToGallery(context, videoFile, callback)
            }, 1000)
            return
        }
        showProgressDialog(context) // 拦截弹窗（阻止操作）
        // 1. 创建输出目录
        val outputDir = createOutputDirectory(context, videoFile) ?: run {
            // 如果创建目录失败，回调失败状态
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
     * @param context 上下文对象
     * @param videoFile 视频文件
     * @return 创建的目录File对象，失败返回null
     */
    private fun createOutputDirectory(context: Context, videoFile: File): File? {
        // 获取视频文件名（不含扩展名）
        val videoName = videoFile.nameWithoutExtension

        // 在系统相册目录下创建以视频文件名命名的子目录
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
     * 构建FFmpeg命令字符串
     * @param videoFile 输入视频文件
     * @param outputDir 输出目录
     * @return FFmpeg命令字符串
     */
    private fun buildFFmpegCommand(videoFile: File, outputDir: File): String {
        val outputPattern = File(outputDir, "frame_%04d.png").absolutePath

        // 查找可用的系统字体
        val fontPath = findAvailableFont() ?: run {
            Log.w(TAG, "No system font found, using default")
            ""
        }
        // 获取视频名称（不含扩展名）
        val videoName = videoFile.nameWithoutExtension

        return if (fontPath.isNotEmpty()) {
            // 添加带外轮廓的水印（使用shadow效果模拟描边）
            // TODO 并行优化

            /*

             // 添加硬件解码和线程优化
    return "-hwaccel auto -threads 4 " +
           "-i ${videoFile.absolutePath} " +
           "-vf \"fps=30,scale=w='if(gt(iw,ih),1280,-2)':h='if(gt(iw,ih),-2,720)'\" " + // 限制分辨率
           "-q:v 2 -preset ultrafast " + // 快速编码预设
           "-pix_fmt yuv420p " + // 兼容性更好的像素格式
           File(outputDir, "frame_%04d.jpg").absolutePath // 改用更高效的jpg格式

             */
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

    /**
     * 查找可用的系统字体
     * @return 找到的字体路径，找不到返回null
     */
    private fun findAvailableFont(): String? {
        return systemFonts.firstOrNull { File(it).exists() }
    }

    /**
    * 执行FFmpeg命令
    * @param context 上下文对象
    * @param command FFmpeg命令字符串
    * @param outputDir 输出目录
    * @param callback 处理完成回调
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

        // 定义执行函数（支持重试）
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

                        // 取消弹窗屏蔽罩
                        if (PercentProgressFFMPG > 0.8) {
                            Handler(Looper.getMainLooper()).post {
                                progressDialog?.dismiss()
                                progressDialog = null
                                Toast.makeText(context, "照片导出完成", Toast.LENGTH_SHORT).show()
                            }
                        }

                    })
                }.start()
            }, 2000) // 延迟2秒
        }

        attemptExecution()

    }


    /**
    * 将时间字符串转换为毫秒数
    * @param durationStr 时间字符串（格式：HH:MM:SS.ss）
    * @return 毫秒数
    */
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


    /**
    * 检查文件是否可用（未被占用）
    * @param file 要检查的文件
    * @return 是否可用
    */
    private fun isFileReady(file: File): Boolean {
        return try {
            file.renameTo(file) // 尝试重命名到自身，检查文件是否被锁定
            true
        } catch (e: Exception) {
            false
        }
    }


    /**
    * 将帧图片批量添加到媒体库
    * @param context 上下文对象
    * @param outputDir 包含帧图片的目录
    */
    // TODO 并行处理帧图片写入
    private fun addFramesToMediaStore(context: Context, outputDir: File) {
        outputDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".png")) {
                addImageToMediaStore(context, file, outputDir.name)
            }
        }
    }

    /**
    * 将单张图片添加到媒体库
    * @param context 上下文对象
    * @param file 图片文件
    * @param albumName 相册名称
    * @return 插入的URI，失败返回null
    */
    // TODO 批量插入优化
    private fun addImageToMediaStore(context: Context, file: File, albumName: String): Uri? {
        // 设置媒体库元数据
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
                // 写入文件内容
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