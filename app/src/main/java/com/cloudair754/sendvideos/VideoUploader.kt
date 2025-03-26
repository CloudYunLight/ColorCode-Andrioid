package com.cloudair754.sendvideos

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

object VideoUploader {

    private const val TAG = "VideoUploader"
    private val client = OkHttpClient()

    fun uploadVideo(file: File, callback: (Boolean) -> Unit) {
        Log.d(TAG, "Attempting to upload file: ${file.name}")

        val shortFileName = generateShortFileName(file.name)
        Log.d(TAG, "Original: ${file.name}, Short: $shortFileName")

        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (file.renameTo(file)) {
                    timer.cancel()
                    performUpload(file, callback)
                }
            }
        }, 1500, 1500)
    }

    private fun performUpload(file: File, callback: (Boolean) -> Unit) {
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
            .url("http://192.168.207.9:5000/upload")
            .post(requestBody)
            .addHeader("X-File-Name", shortFileName)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Upload failed", e)
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {




                Log.d(TAG, "Server response code: ${response.code}")
                callback(response.isSuccessful)
            }
        })
    }

    fun generateShortFileName(originalName: String): String {
        val uuid = UUID.randomUUID().toString().take(8)
        return "${uuid}.${originalName.substringAfterLast(".")}"
    }
}