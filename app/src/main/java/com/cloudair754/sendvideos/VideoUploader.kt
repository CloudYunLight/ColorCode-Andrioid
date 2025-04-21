package com.cloudair754.sendvideos

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch

/**
 * 视频上传器（单例对象）
 * 功能：
 * 1. 分片上传视频文件到服务器（每片20MB）
 * 2. 支持文件锁定检查
 * 3. 提供上传结果回调
 * 4. 自动生成短文件名
 */
object VideoUploader {

    // 网络状态检查器
    private var networkChecker: NetworkStatusChecker? = null
    fun setNetworkChecker(checker: NetworkStatusChecker) {
        this.networkChecker = checker
    }

    private const val TAG = "VideoUploader"
    // 分片大小 (20MB)
    private const val CHUNK_SIZE = 20 * 1024 * 1024
    // 最大并发上传数
    private const val MAX_CONCURRENT_UPLOADS = 3
    // 文件锁定检查间隔（毫秒）
    private const val FILE_LOCK_CHECK_INTERVAL = 1500L

    // OkHttpClient配置（连接超时30秒，读写超时60秒）
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 上传视频文件（保持原有调用方式不变）
     * @param context 上下文对象
     * @param file 要上传的视频文件
     * @param callback 上传结果回调（成功/失败）
     */
    fun uploadVideo(context: Context, file: File, callback: (Boolean) -> Unit) {
        // 转换为新的回调方式但不暴露给外部
        internalUploadVideo(context, file) { success, _ ->
            callback(success)
        }
    }

    /**
     * 实际的分片上传实现（内部使用）
     * @param context 上下文对象
     * @param file 要上传的视频文件
     * @param callback 上传结果回调（返回成功状态和服务器返回的任务ID）
     */
    private fun internalUploadVideo(
        context: Context,
        file: File,
        callback: (Boolean, String?) -> Unit
    ) {
        // 检查网络状态
        networkChecker?.let { checker ->
            if (checker.currentNetworkQuality == NetworkStatusChecker.NetworkQuality.POOR) {
                Log.w(TAG, "[Network] Poor network quality, upload canceled")
                showToast(context, "网络质量差，已取消上传")
                callback(false, null)
                return
            }
        }

        Log.i(TAG, "[Upload] Preparing to upload file: ${file.name} (${file.length()} bytes)")
        showToast(context, "准备上传视频...")

        // 使用定时器确保文件可访问（解决文件锁定问题）
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                // 检查文件是否可访问
                if (file.renameTo(file)) {
                    timer.cancel()
                    Log.d(TAG, "[FileLock] File is now accessible, proceeding with upload")

                    // 生成文件ID和短文件名
                    val fileId = UUID.randomUUID().toString()
                    val shortFileName = generateShortFileName(file.name)
                    Log.d(TAG, "[Upload] Generated file ID: $fileId")

                    // 计算分片数量（修正类型转换）
                    val totalSize = file.length()
                    val totalChunks = ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
                    Log.d(TAG, "[Upload] Total chunks: $totalChunks")

                    // 开始分片上传
                    uploadChunks(context, file, fileId, shortFileName, totalChunks, callback)
                } else {
                    Log.d(TAG, "[FileLock] File still locked, waiting...")
                }
            }
        }, FILE_LOCK_CHECK_INTERVAL, FILE_LOCK_CHECK_INTERVAL)
    }

    /**
     * 分片上传核心逻辑
     * @param context 上下文对象
     * @param file 要上传的文件
     * @param fileId 文件唯一标识
     * @param shortFileName 短文件名
     * @param totalChunks 总分片数
     * @param callback 上传结果回调
     */
    private fun uploadChunks(
        context: Context,
        file: File,
        fileId: String,
        shortFileName: String,
        totalChunks: Int,
        callback: (Boolean, String?) -> Unit
    ) {
        // 创建固定大小的线程池（控制并发数）
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS)
        // 使用CountDownLatch跟踪所有分片上传完成
        val latch = CountDownLatch(totalChunks)
        // 成功计数器（原子操作）
        var successCount = AtomicInteger(0)
        // 服务器返回的任务ID
        var taskId: String? = null
        // 错误标志（原子操作）
        var hasError = AtomicBoolean(false)

        Log.i(TAG, "[Upload] Starting chunk upload with $MAX_CONCURRENT_UPLOADS concurrent threads")

        // 遍历所有分片
        for (i in 0 until totalChunks) {
            executor.execute {
                // 如果已发生错误，跳过后续分片
                if (hasError.get()) {
                    Log.w(TAG, "[Upload] Error detected, skipping chunk $i")
                    latch.countDown()
                    return@execute
                }

                try {
                    // 读取分片数据
                    val startPos = i * CHUNK_SIZE.toLong()
                    val endPos = minOf((i + 1) * CHUNK_SIZE.toLong(), file.length())
                    val chunkSize = (endPos - startPos).toInt()
                    Log.d(TAG, "[Upload] Processing chunk $i/$totalChunks (bytes $startPos-$endPos)")

                    // 读取分片数据到缓冲区
                    val buffer = ByteArray(chunkSize)
                    FileInputStream(file).use { fis ->
                        fis.skip(startPos)
                        fis.read(buffer)
                    }

                    // 创建临时文件保存分片
                    val tempFile = File.createTempFile("chunk_$i", ".tmp").apply {
                        deleteOnExit()
                    }
                    FileOutputStream(tempFile).use { fos ->
                        fos.write(buffer)
                    }
                    Log.d(TAG, "[Upload] Chunk $i saved to temp file")

                    // 构建多部分表单请求
                    val uploadUrl = getUploadUrl(context)
                    val mediaType = "video/mp4".toMediaType()

                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", shortFileName, tempFile.asRequestBody(mediaType))
                        .addFormDataPart("chunk_number", i.toString())
                        .addFormDataPart("total_chunks", totalChunks.toString())
                        .addFormDataPart("file_id", fileId)
                        .addFormDataPart("original_filename", file.name)
                        .build()


                    // 构建HTTP请求
                    val request = Request.Builder()
                        .url(uploadUrl)
                        .post(requestBody)
                        .addHeader("X-File-Name", shortFileName)
                        .build()

                    Log.d(TAG, "[Upload] Sending chunk $i to server")
                    // 执行上传请求
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.e(TAG, "[Upload] Chunk $i upload failed: ${response.code}")
                            hasError.set(true)
                            return@use
                        }

                        // 解析服务器响应
                        val responseBody = response.body?.string()
                        Log.d(TAG, "[Upload] Chunk $i response: $responseBody")

                        // 如果是最后一个分片，获取任务ID
                        if (i == totalChunks - 1) {
                            responseBody?.let {
                                try {
                                    val json = JSONObject(it)
                                    taskId = json.optString("task_id")
                                    Log.i(TAG, "[Upload] Received task ID: $taskId")
                                } catch (e: Exception) {
                                    Log.e(TAG, "[Upload] Failed to parse task ID", e)
                                }
                            }
                        }

                        successCount.incrementAndGet() // 增加成功计数
                    }

                    tempFile.delete() // 删除临时文件
                } catch (e: Exception) {
                    Log.e(TAG, "[Upload] Error uploading chunk $i", e)
                    hasError.set(true) // 设置错误标志
                } finally {
                    latch.countDown()// 减少计数
                    Log.d(TAG, "[Upload] Chunk $i processing completed")
                }
            }
        }

        // 等待所有分片上传完成
        // 关闭线程池并等待所有任务完成（最多等待5分钟）
        executor.shutdown()
        try {
            if (!latch.await(5, TimeUnit.MINUTES)) {
                Log.e(TAG, "[Upload] Upload timeout (5 minutes)")
                showToast(context, "上传超时")
                callback(false, null)
                return
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "[Upload] Upload interrupted", e)
            callback(false, null)
            return
        }

        // 根据上传结果调用回调
        if (hasError.get()) {
            Log.e(TAG, "[Upload] Upload failed with errors. Success chunks: ${successCount.get()}/$totalChunks")
            showToast(context, "上传失败，部分分片上传出错")
            callback(false, null)
        } else {
            Log.i(TAG, "[Upload] All chunks uploaded successfully. Total: $totalChunks")
            showToast(context, "视频上传完成")

            // 显示任务ID弹窗
            taskId?.let { id ->
                Handler(Looper.getMainLooper()).post {
                    AlertDialog.Builder(context)
                        .setTitle("上传成功")
                        .setMessage("任务ID: $id")
                        .setPositiveButton("确定") { dialog, _ ->
                            dialog.dismiss()
                            // 开始轮询任务状态
                            pollTaskStatus(context, id) { success, result ->
                                if (!success) {
                                    Log.w(TAG, "[Polling] Task $id polling failed")
                                }
                            }
                        }
                        .create()
                        .show()
                }
            }

            callback(true, taskId)  // 这里只是通知上传完成
        }
    }

    /**
     * 从SharedPreferences获取上传URL
     * @param context 上下文对象
     * @return 上传URL（默认为http://IP:5000/upload）
     */
    private fun getUploadUrl(context: Context): String {
        val sharedPref = context.getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE)
        return sharedPref.getString("upload_url", "http://IP:5000/upload")
            ?: "http://IP:5000/upload"
    }

    /**
     * 显示服务器响应对话框（在主线程）
     * @param context 上下文对象
     * @param title 对话框标题
     * @param message 对话框消息
     */
    private fun showResponseDialog(context: Context, title: String, message: String) {
        Handler(Looper.getMainLooper()).post {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
                .create()
                .show()
        }
    }

    /**
     * 在主线程显示Toast消息
     * @param context 上下文对象
     * @param message 要显示的消息
     */
    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 生成短文件名（基于UUID）
     * @return 8位UUID + 原始文件扩展名
     */
    fun generateShortFileName(originalName: String): String {
        val uuid = UUID.randomUUID().toString().take(8)
        return "${uuid}.${originalName.substringAfterLast(".")}"
    }

    // 在VideoUploader对象中添加以下方法

    /**
     * 轮询任务状态（改进版：达到预期结果时自动停止）
     * @param context 上下文对象
     * @param taskId 要查询的任务ID
     * @param interval 轮询间隔（毫秒，默认2秒）
     * @param maxAttempts 最大尝试次数（默认30次≈1分钟）
     * @param callback 结果回调（成功时返回结果JSON）
     */
    fun pollTaskStatus(
        context: Context,
        taskId: String,
        interval: Long = 2000,
        maxAttempts: Int = 30,
        callback: (Boolean, JSONObject?) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        var shouldContinue = true // 控制是否继续轮询的标志

        // 内部递归函数实现轮询
        fun doPoll() {
            if (!shouldContinue) return // 如果标志为false则停止

            attempts++
            Log.d(TAG, "[Polling] Checking status for task $taskId (attempt $attempts/$maxAttempts)")

            val statusUrl = getStatusUrl(context) + "/" + taskId
            val request = Request.Builder()
                .url(statusUrl)
                .get()
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "[Polling] Failed to check status", e)
                    if (attempts >= maxAttempts) {
                        handler.post {
                            showToast(context, "状态查询失败")
                            callback(false, null)
                        }
                    } else if (shouldContinue) {
                        handler.postDelayed(::doPoll, interval)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string()
                        Log.d(TAG, "[Polling] Status response: $body")

                        val json = JSONObject(body)
                        when (json.getString("status")) {
                            "completed" -> {
                                shouldContinue = false // 停止轮询
                                handler.post {
                                    showResultDialog(context, json.getJSONObject("result"))
                                    callback(true, json)
                                }
                            }
                            else -> {
                                if (attempts >= maxAttempts) {
                                    handler.post {
                                        showToast(context, "处理超时，请稍后手动检查")
                                        callback(false, null)
                                    }
                                } else if (shouldContinue) {
                                    handler.postDelayed(::doPoll, interval)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[Polling] Error parsing response", e)
                        handler.post {
                            showToast(context, "状态解析错误")
                            callback(false, null)
                        }
                    }
                }
            })
        }

        // 开始轮询
        handler.post(::doPoll)

        // 可选：提供取消轮询的方法
        /*
        return object {
            fun cancel() {
                shouldContinue = false
                handler.removeCallbacks(::doPoll)
            }
        }
        */
    }

    /**
     * 显示处理结果对话框
     */
    private fun showResultDialog(context: Context, result: JSONObject) {
        val message = buildString {
            append("视频处理完成！\n\n")
            append("时长: ${result.optInt("duration")}秒\n")
            append("大小: ${result.optInt("size")}KB\n")
            append("URL: ${result.optString("video_url")}\n")
            append("图片信息：${result.optString("info")}")
        }

        AlertDialog.Builder(context)
            .setTitle("处理结果")
            .setMessage(message)
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    /**
     * 获取状态检查URL
     */
    private fun getStatusUrl(context: Context): String {
        val sharedPref = context.getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("upload_url", "http://IP:5000") ?: "http://IP:5000"
        return baseUrl.replace("/upload", "") + "/check_status"
    }





}