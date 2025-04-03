package com.cloudair754.sendvideos

import android.util.Log
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 视频上传器（单例对象）
 * 功能：
 * 1. 处理视频文件上传到服务器
 * 2. 支持上传进度监控
 * 3. 提供上传结果回调
 * 4. 自动生成短文件名
 */
object VideoUploader {

    private const val TAG = "VideoUploader"
    private val client = OkHttpClient()// OkHttp客户端实例

    /**
     * 上传视频文件（公开接口）
     * @param context 上下文对象
     * @param file 要上传的视频文件
     * @param callback 上传结果回调（成功/失败）
     */
    fun uploadVideo(context: Context, file: File, callback: (Boolean) -> Unit) {
        Log.d(TAG, "Attempting to upload file: ${file.name}")
        showToast(context, "准备上传视频...")

        // 生成短文件名（用于显示和上传）
        val shortFileName = generateShortFileName(file.name)
        Log.d(TAG, "Original: ${file.name}, Short: $shortFileName")

        // 使用定时器确保文件可访问（解决文件锁定问题）
        // TODO 加入协程；加入超时放弃
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                // 检查文件是否可访问
                if (file.renameTo(file)) {
                    timer.cancel()
                    performUpload(context, file, callback)// 执行实际上传
                }
            }
        }, 1500, 1500)// 延迟1.5秒开始，每1.5秒检查一次
    }

    // 添加这个方法获取配置的URL
    private fun getUploadUrl(context: Context): String {
        val sharedPref = context.getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE)
        return sharedPref.getString("upload_url", "http://IP:5000/upload")
            ?: "http://IP:5000/upload"
        // 其实这里的双重保护没什么用，因为我的主机IP频繁变换
    }

    /**
     * 执行实际上传操作
     * @param file 要上传的视频文件
     * @param callback 上传结果回调
     */
    private fun performUpload(context: Context, file: File, callback: (Boolean) -> Unit) {


        val uploadUrl = getUploadUrl(context) //"http://10.195.152.71:5000/upload"
        // 使用uploadUrl代替硬编码的URL
        Log.d(TAG, "Attempting to upload to URL: $uploadUrl")

        // 生成短文件名并设置媒体类型
        val shortFileName = generateShortFileName(file.name)
        val mediaType = "video/mp4".toMediaType()

        // 构建多部分表单请求体（适配Flask服务器接收格式）
        val requestBody = MultipartBody.Builder()  // 关键修复点！！！！
            .setType(MultipartBody.FORM) // 设置表单类型
            //添加一个表单数据部分；；表单字段名(name)，通常对应服务器端接收文件的参数名
            .addFormDataPart(
                "file", // 表单字段名（对应服务器参数名）
                shortFileName,// 文件名
                file.asRequestBody(mediaType)
            )
            .build()


        // 构建HTTP请求
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)// POST请求
            .addHeader("X-File-Name", shortFileName)
            .build()


        // 异步执行上传请求
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 上传失败处理
                Log.e(TAG, "Upload failed to $uploadUrl", e)
                showToast(context, "上传失败: ${e.message}")

                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (success) {

                    showToast(context, "上传成功: $uploadUrl ")
                    // toast 需要在主线程运行

                } else {
                    showToast(
                        context,
                        "上传失败，错误码: ${response.code}"
                    )
                    Log.e(TAG, "Upload to $uploadUrl failed with code: ${response.code}")
                    Log.e(TAG, "Response body: ${response.body?.string()}")
                }
                Log.d(TAG, "Server response code: ${response.code}")
                callback(success)

            }
        })
    }

    /**
     * 在主线程显示Toast消息
     * @param message 要显示的消息内容
     */
    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }


    /**
     * 生成短文件名（基于UUID）
     * @param originalName 原始文件名
     * @return 8位UUID + 原始文件扩展名
     */
    fun generateShortFileName(originalName: String): String {
        val uuid = UUID.randomUUID().toString().take(8)
        return "${uuid}.${originalName.substringAfterLast(".")}"
    }
}