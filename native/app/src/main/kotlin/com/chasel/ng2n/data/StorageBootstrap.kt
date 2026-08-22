package com.chasel.ng2n.data

import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存储层的显式 bootstrap —— **修 P2-04** 的落点。
 *
 * 审计的原话:「把 DB 初始化移到显式 bootstrap 阶段,允许延迟加载和空缓存降级;
 * 记录 schema/打开错误但不阻断根模块导入」。RN 版是在 zustand store 的模块初始化里
 * 同步开库并扫元数据(`store/topic-cache.ts:33` 的 `loadMeta()`、
 * `store/history.ts:30`、`store/notifications.ts:43`),那三行就在 import 链上,
 * 冷启动第一帧之前必然跑完。
 *
 * 这一版:[start] 只往 IO scope 上扔协程就返回,**首屏一秒都不等**;
 * 数据读回来之后仓库的 StateFlow 自己会让订阅方重渲一次。读失败也只是空数据 ——
 * 历史页空着、缓存列表空着,app 照常能用。
 *
 * 谁在这里预热是有讲究的:
 * - 历史与帖子缓存**要**,它们的内存镜像是同步读口的事实来源;
 * - 设置 / 账号 / 诊断日志**不要** —— 它们全是 Flow,订阅时自然会读,
 *   预热只会平白多一次文件打开。
 */
@Singleton
class StorageBootstrap @Inject constructor(
  private val history: HistoryRepository,
  private val topicCache: TopicCacheRepository,
  @IoScope private val scope: CoroutineScope,
) {

  fun start() {
    scope.launch { runCatching { history.warmUp() } }
    scope.launch { runCatching { topicCache.warmUp() } }
  }
}
