package com.haven.guiqi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * NetworkMonitor — 断网提示条
 *
 * 用 ConnectivityManager.NetworkCallback 监听网络变化，
 * 断网时显示顶部提示条，恢复时自动隐藏。
 * 生命周期跟着 Activity 走：onCreate 里 start()，onDestroy 里 stop()。
 */
class NetworkMonitor(
    private val context: Context,
    private val banner: TextView
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { banner.visibility = View.GONE }
        }

        override fun onLost(network: Network) {
            // onLost 可能对每个 network 单独触发，需要确认是真的全部断了
            handler.post {
                if (!hasConnectivity()) {
                    banner.visibility = View.VISIBLE
                }
            }
        }
    }

    /** 开始监听（Activity.onCreate 时调用） */
    fun start() {
        if (registered) return
        // 先检查当前状态
        if (!hasConnectivity()) {
            banner.visibility = View.VISIBLE
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
    }

    /** 停止监听（Activity.onDestroy 时调用） */
    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        registered = false
    }

    private fun hasConnectivity(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}