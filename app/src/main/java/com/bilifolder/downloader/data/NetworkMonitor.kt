package com.bilifolder.downloader.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络状态监听（设计 4.7.1，需求 15）。
 *
 * 基于 `ConnectivityManager.registerDefaultNetworkCallback` 输出：
 * - `Connected(wifi)` / `Connected(cellular)` / `Disconnected`
 *
 * `DownloadManager` 订阅该流实现"仅 WiFi 模式"下的暂停/恢复；
 * UI 亦可用于展示当前网络状态。需要 `ACCESS_NETWORK_STATE` 权限。
 */
class NetworkMonitor(context: Context) {

    enum class NetworkState { WIFI, CELLULAR, DISCONNECTED }

    private val _networkState = MutableStateFlow(NetworkState.DISCONNECTED)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateState()
        override fun onLost(network: Network) = updateState()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = updateState()
    }

    /** 注册默认网络回调；重复调用幂等 */
    fun start() {
        if (registered) return
        registered = true
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
        updateState()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun updateState() {
        val state = runCatching {
            val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            when {
                caps == null -> NetworkState.DISCONNECTED
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.WIFI
                else -> NetworkState.DISCONNECTED
            }
        }.getOrDefault(NetworkState.DISCONNECTED)
        LogUtil.d(TAG, "网络状态: $state")
        _networkState.value = state
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
