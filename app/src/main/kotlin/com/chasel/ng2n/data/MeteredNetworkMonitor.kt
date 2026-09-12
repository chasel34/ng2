package com.chasel.ng2n.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.chasel.ng2n.core.local.MeteredNetworkSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前网络是不是计费网络(「仅 Wi-Fi 下加载图片」与「图片加载策略」的智能档要用)。
 *
 * 订阅只建一次:一屏帖子里几十张图,每张自己订一次就是几十个系统回调。做成
 * `@Singleton`,组件只读这个 [StateFlow]。RN 侧同构件是 `src/store/network.ts`。
 *
 * **对 RN 版的有意偏离**:RN 用 expo-network,只把 `type === CELLULAR` 算计费,
 * 于是「手机热点 / 计费 Wi-Fi」被当成不限流,该省流量的时候照拉原图。这里改用
 * ConnectivityManager 的 `NET_CAPABILITY_NOT_METERED` —— 系统自己的计费判定,
 * 覆盖热点与用户在设置里手动标为「按流量计费」的 Wi-Fi。字段名(RN 那边就叫
 * `metered`)与设置项文案(「仅 Wi-Fi 下加载图片」)本来说的就是这件事。
 *
 * 拿不到的时候(没有活动网络、飞行模式刚切回来)按**不限流**走 —— 与 RN 侧同一条兜底:
 * 宁可多花一点流量,也别让人在正常 Wi-Fi 下看到满屏「点击显示图片」还不知道为什么。
 */
@Singleton
class MeteredNetworkMonitor @Inject constructor(
  @ApplicationContext context: Context,
) : MeteredNetworkSource {

  private val _metered = MutableStateFlow(false)
  override val metered: StateFlow<Boolean> = _metered.asStateFlow()

  private val connectivity = context.getSystemService(ConnectivityManager::class.java)

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
      _metered.value = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun onLost(network: Network) {
      // 断网时不折图:折了只会让人以为是省流量开关的锅
      _metered.value = false
    }
  }

  init {
    // 整块 runCatching:这两个调用都要 ACCESS_NETWORK_STATE(manifest 里有),
    // 但「查一下是不是流量」绝不该有能力把整个 app 拦在启动那一步 —— 抛了就按不限流走。
    runCatching {
      // 回调只在状态**变化**时才响,不先问一次就要等到第一次切换网络
      val current = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
      if (current != null) {
        _metered.value = !current.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      }
      connectivity?.registerDefaultNetworkCallback(callback)
    }
  }
}
