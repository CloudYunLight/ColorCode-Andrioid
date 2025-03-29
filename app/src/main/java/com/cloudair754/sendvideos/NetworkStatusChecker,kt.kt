package com.cloudair754.sendvideos

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class NetworkStatusChecker(
    private val context: Context,
    private val updateStatus: (drawableRes: Int, statusText: String) -> Unit // 统一使用updateStatus
) {
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private val pingInterval = 500L // 0.5秒
    private val pingRunnable = object : Runnable {
        override fun run() {
            checkNetworkStatus()
            handler.postDelayed(this, pingInterval)
        }
    }

    private val sharedPref = context.getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE)

    fun startChecking() {
        handler.post(pingRunnable)
    }

    fun stopChecking() {
        handler.removeCallbacks(pingRunnable)
    }

    private fun checkNetworkStatus() {
        val savedUrl = sharedPref.getString("upload_url", "") ?: ""
        if (savedUrl.isEmpty()) {
            updateStatus(R.drawable.circle_red, "未设置服务器")
            return
        }

        val pingUrl = savedUrl.replace("/upload", "/ping")
        val request = Request.Builder()
            .url(pingUrl)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateStatus(R.drawable.circle_red, "连接失败")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseData = response.body?.string()
                    if (response.isSuccessful && responseData != null) {
                        val json = JSONObject(responseData)
                        val responseTime = json.getDouble("response_time")
                        val status = json.getString("status")

                        when {
                            status != "alive" -> updateStatus(R.drawable.circle_red, "服务器异常")
                            responseTime > 0.01 -> updateStatus(
                                R.drawable.circle_yellow,
                                "延迟较高 ${"%.1f".format(responseTime * 1000)}ms"
                            )
                            else -> updateStatus(
                                R.drawable.circle_green,
                                "连接正常 ${"%.1f".format(responseTime * 1000)}ms"
                            )
                        }
                    } else {
                        updateStatus(R.drawable.circle_red, "服务器无响应")
                    }
                } catch (e: Exception) {
                    updateStatus(R.drawable.circle_red, "解析错误")
                }
            }
        })
    }
}