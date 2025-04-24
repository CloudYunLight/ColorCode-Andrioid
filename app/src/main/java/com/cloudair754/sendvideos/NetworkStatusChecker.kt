package com.cloudair754.sendvideos

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网络状态检查器
 * 功能：
 * 1. 定时检查服务器连接状态
 * 2. 根据响应结果更新UI状态指示
 * 3. 支持开始/停止检查
 */
class NetworkStatusChecker(
    private val context: Context,
    // 状态更新回调，参数：图标资源ID和状态文本
    private val updateStatus: (drawableRes: Int, statusText: String) -> Unit // 统一使用updateStatus
) {

    enum class NetworkQuality {
        GOOD, // 良好网络
        FAIR, // 一般网络
        POOR  // 差网络
    }

    // 添加网络质量状态属性
    @Volatile
    var currentNetworkQuality: NetworkQuality = NetworkQuality.POOR
        private set

    private val pingInterval = 500L // 0.5秒

    // OkHttp客户端实例
    //private val client = OkHttpClient()

    // 修改OkHttpClient配置，添加超时设置
    private val client = OkHttpClient.Builder()
        .connectTimeout(pingInterval * 2, TimeUnit.MILLISECONDS) // 连接超时
        .readTimeout(pingInterval * 2, TimeUnit.MILLISECONDS)    // 读取超时
        .writeTimeout(pingInterval * 2, TimeUnit.MILLISECONDS)   // 写入超时
        .callTimeout(pingInterval * 2, TimeUnit.MILLISECONDS)    // 整个调用超时
        .build()

    // 主线程Handler，用于UI更新
    private val handler = Handler(Looper.getMainLooper())


    // 定时检查任务
    private val pingRunnable = object : Runnable {
        override fun run() {
            checkNetworkStatus()
            handler.postDelayed(this, pingInterval)
        }
    }

    // 共享偏好设置，用于获取服务器URL
    private val sharedPref = context.getSharedPreferences("SendVideosPrefs", Context.MODE_PRIVATE)

    fun startChecking() {
        handler.post(pingRunnable)
    }

    fun stopChecking() {
        handler.removeCallbacks(pingRunnable)
    }


    /**
     * 检查网络状态（主要检测逻辑）
     * 逻辑流程：
     * 1. 检查是否配置了服务器URL
     * 2. 向服务器发送ping请求
     * 3. 根据响应结果更新状态
     */
    private fun checkNetworkStatus() {
        val BaseUrl = sharedPref.getString("Base_url", "") ?: ""



        if (BaseUrl.isEmpty()) {
            updateStatus(R.drawable.circle_red, "未设置服务器")
            currentNetworkQuality = NetworkQuality.POOR
            return
        }

        val pingUrl = "$BaseUrl/ping"

        // 创建HTTP请求
        val request = Request.Builder()
            .url(pingUrl)
            .build()


        // 异步发送请求
        client.newCall(request).enqueue(object : Callback {

            // 请求失败回调
            override fun onFailure(call: Call, e: IOException) {
                currentNetworkQuality = NetworkQuality.POOR
                updateStatus(R.drawable.circle_red, "连接失败")
            }

            // 请求成功回调
            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseData = response.body?.string()
                    if (response.isSuccessful && responseData != null) {
                        val json = JSONObject(responseData)
                        val responseTime = json.getDouble("response_time")
                        val status = json.getString("status")

                        currentNetworkQuality = when {
                            status != "alive" -> NetworkQuality.POOR
                            responseTime > 0.01 -> NetworkQuality.FAIR
                            else -> NetworkQuality.GOOD
                        }

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
                        currentNetworkQuality = NetworkQuality.POOR
                        updateStatus(R.drawable.circle_red, "服务器无响应")
                    }
                } catch (e: Exception) {
                    currentNetworkQuality = NetworkQuality.POOR
                    updateStatus(R.drawable.circle_red, "解析错误")
                }
            }
        })
    }
}