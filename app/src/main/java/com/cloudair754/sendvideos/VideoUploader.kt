package com.cloudair754.sendvideos

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.IOException

object VideoUploader {

    private const val TAG = "VideoUploader" // Log tag for debugging
    private val client = OkHttpClient()

    /**
     * 上传视频文件到服务器。
     * @param file 要上传的视频文件。
     * @param callback 上传结果的回调函数。
     */
    fun uploadVideo(file: File, callback: (Boolean) -> Unit) {
        Log.d(TAG, "Attempting to upload file: ${file.name}")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, RequestBody.create("video/mp4".toMediaType(), file))
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1/upload")  // 替换为你的Python后端地址
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Upload failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Upload successful.")
                    callback(true)
                } else {
                    Log.e(TAG, "Upload failed with response code: ${response.code}")
                    callback(false)
                }
            }
        })
    }
}