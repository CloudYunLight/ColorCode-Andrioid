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


object VideoUploader {

    private const val TAG = "VideoUploader"
    private val client = OkHttpClient()


    fun uploadVideo(context: Context , file: File, callback: (Boolean) -> Unit) {
        Log.d(TAG, "Attempting to upload file: ${file.name}")
        showToast(context, "Preparing to upload video...")

        val shortFileName = generateShortFileName(file.name)
        Log.d(TAG, "Original: ${file.name}, Short: $shortFileName")

        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (file.renameTo(file)) {
                    timer.cancel()
                    performUpload(context,file, callback)
                }
            }
        }, 1500, 1500)
    }

    private fun performUpload(context: Context,file: File, callback: (Boolean) -> Unit) {
        val shortFileName = generateShortFileName(file.name)
        val mediaType = "video/mp4".toMediaType()

        // 此语句经过修复，主要是为了贴合Flask==》'file' not in request.files的需要
        val requestBody = MultipartBody.Builder()  // 关键修复点！！！！
            .setType(MultipartBody.FORM)
            //添加一个表单数据部分；；表单字段名(name)，通常对应服务器端接收文件的参数名
            .addFormDataPart(
                "file",
                shortFileName,
                file.asRequestBody(mediaType)
            )
            .build()

        val request = Request.Builder()
            .url("http://10.195.152.71:5000/upload")
            .post(requestBody)
            .addHeader("X-File-Name", shortFileName)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Upload failed", e)
                showToast(context, "Upload failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (success) {
                    file.delete() // 上传成功后删除本地文件
                    // TODO 这里的争议中心，应该放在录制开始之时，他们建立了两个文件
                    showToast(context, "Upload successful!")
                    // toast 需要在主线程运行

                }
                else{
                    showToast(context,
                        "Upload failed with code: ${response.code}")
                }
                Log.d(TAG, "Server response code: ${response.code}")
                callback(success)

            }
        })
    }

    // 确保 Toast 在主线程显示
    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun generateShortFileName(originalName: String): String {
        val uuid = UUID.randomUUID().toString().take(8)
        return "${uuid}.${originalName.substringAfterLast(".")}"
    }
}