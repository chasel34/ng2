package com.chasel.ng2n.ui.nav

import android.content.Intent
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统深链的收件箱 —— `MainActivity` 收 intent,导航宿主取件。
 *
 * ## 为什么隔一个单例
 *
 * back stack 活在 `Ng2nApp` 的 composition 里,而 intent 到达的是 Activity;
 * 冷启动时 intent 比第一次 composition 还早。收件箱把这两个时刻解耦:
 * Activity 只管投递,宿主起来之后取一次(取完清空,避免配置变化后重复跳转)。
 *
 * ## 「垫首页防死返回」
 *
 * RN 侧靠 expo-router 的 `unstable_settings.anchor='index'`;Android 的常规写法是
 * `TaskStackBuilder.addNextIntentWithParentStack`。这一版**两者都不需要**,得到的是
 * 同一个结果:Nav3 的 back stack 由 `rememberNavBackStack(Home)` **恒以首页开局**,
 * 深链目标是 `add` 上去的第二格 —— 从深链进来的主题按返回必回首页,不会直接退出 app。
 * (`TaskStackBuilder` 在单 Activity 架构里是退化的:它铺的是 Activity 栈,而这里
 * 只有一个 Activity;真去调它只会把自己重启一遍。)
 *
 * ## 只认 `ng2n://`
 *
 * manifest 里**没有注册** NGA 域名的 intent-filter(spec §三:并行期不跟 RN 版抢
 * `https://bbs.nga.cn` 的打开权)。解析器本身照样认完整 URL —— 抽屉「由 URL 读取」
 * 粘进来的就是那种,走的是同一张映射表。
 */
object DeepLinkInbox {

  private val state = MutableStateFlow<NavKey?>(null)

  /** 导航宿主订阅它;取完调 [consume] 清空。 */
  val pending: StateFlow<NavKey?> = state.asStateFlow()

  /**
   * 收一条 intent。不是 `ACTION_VIEW`、或者链接解不出目标,就什么都不做
   * (开发客户端自己的启动 URL、以及没带深链的冷启动都从这个口子过)。
   *
   * @return 认下了这条链接就返回 true(只用于日志/测试)。
   */
  fun offer(intent: Intent?): Boolean {
    val raw = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return false
    val key = navKeyForLink(raw) ?: return false
    state.value = key
    return true
  }

  /** 取件并清空。 */
  fun consume() {
    state.value = null
  }
}
