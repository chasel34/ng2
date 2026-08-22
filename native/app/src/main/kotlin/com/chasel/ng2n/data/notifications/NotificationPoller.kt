package com.chasel.ng2n.data.notifications

import com.chasel.ng2n.core.api.NgaNotification
import com.chasel.ng2n.core.api.fetchNotificationFeed
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知(「最近被喷」)的前台轮询 —— 直译 RN 侧 `store/notifications.ts` 的那一半。
 *
 * 服务端没有推送,也没有逐条已读(API 文档 §9.1);客户端每 [POLL_INTERVAL_MS]
 * 拉一次 `get_all`,条目**只增不覆盖**([mergeNotifications]),未读数 = 条目里
 * 不在已读集合(票 14 的 Room `notification_read`,按 uid 分桶)里的那些。
 *
 * 放在 `data/notifications` 而不是抽屉里:**票 17 的通知屏要复用同一份**条目与已读集合
 * —— 两处各拉一次等于把请求翻倍(ADR-0002)。抽屉只订阅 [unread] 画那颗角标。
 *
 * 生命周期由 UI 层的「前台」驱动([start] / [stop]):RN 版也只在前台轮询,
 * 后台轮询既没有用(没有通知栏推送)又白烧流量与限流额度。
 */
@Singleton
class NotificationPoller @Inject constructor(
  private val client: NgaClient,
  private val accounts: AccountStore,
  private val readRepository: NotificationReadRepository,
  private val settings: SettingsStore,
  @IoScope private val scope: CoroutineScope,
) {

  private val items = MutableStateFlow<List<NgaNotification>>(emptyList())
  val notifications: StateFlow<List<NgaNotification>> = items.asStateFlow()

  private val unreadCount = MutableStateFlow(0)
  val unread: StateFlow<Int> = unreadCount.asStateFlow()

  private val failure = MutableStateFlow<Throwable?>(null)
  val lastError: StateFlow<Throwable?> = failure.asStateFlow()

  private var job: Job? = null
  private var subscribers = 0

  /** 前台开始轮询。可重入:多个屏幕同时订阅只跑一条循环。 */
  @Synchronized
  fun start() {
    subscribers += 1
    if (job?.isActive == true) return
    job = scope.launch {
      while (isActive) {
        pollOnce()
        delay(POLL_INTERVAL_MS)
      }
    }
  }

  /** 最后一个订阅者走了就停。 */
  @Synchronized
  fun stop() {
    subscribers = (subscribers - 1).coerceAtLeast(0)
    if (subscribers > 0) return
    job?.cancel()
    job = null
  }

  /** 手动拉一次(通知屏的下拉刷新)。 */
  suspend fun refresh() = pollOnce()

  private suspend fun pollOnce() {
    // 票 17c:「启用被喷提示」关掉后不再轮询,抽屉也不显示未读角标
    // (RN 版 `sprayNotice` 的原话)。设置**每轮现读**,改完下一轮就生效。
    if (!settings.currentSettings().sprayNotice) {
      unreadCount.value = 0
      return
    }
    val uid = currentAccountOf(accounts.accounts.first())?.uid
    if (uid == null) {
      // 游客态:接口会报「你必须先登录论坛」,不发请求,角标归零
      items.value = emptyList()
      unreadCount.value = 0
      return
    }
    try {
      val feed = fetchNotificationFeed(client)
      val merged = mergeNotifications(items.value.map(::Wrapped), feed.items.map(::Wrapped))
      items.value = merged.map { it.item }
      unreadCount.value = unreadCount(merged, readRepository.readIds(uid))
      failure.value = null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      // 轮询失败不打扰用户:角标维持上一次的数,原因留给通知屏(票 17)去说
      failure.value = error
    }
  }

  /** 标记一批已读并立刻把角标算新。 */
  suspend fun markRead(ids: List<String>) {
    val uid = currentAccountOf(accounts.accounts.first())?.uid ?: return
    readRepository.markRead(uid, ids)
    unreadCount.value = unreadCount(items.value.map(::Wrapped), readRepository.readIds(uid))
  }

  /**
   * [NotificationLike] 的适配壳。
   *
   * `NgaNotification` 住 `core/api`(零 Android、零 data 依赖),而已读模型的接口
   * [NotificationLike] 住 `data/notifications` —— core 不能反过来依赖 data,
   * 所以在这里包一层。**票外问题**:把 [NotificationLike] 下沉到 core 会更顺,
   * 但那是票 07/14 的地盘,本票不动。
   */
  private data class Wrapped(val item: NgaNotification) : NotificationLike {
    override val id: String get() = item.id
    override val timestamp: Long get() = item.timestamp
  }

  private companion object {
    /** RN 版同值:前台 60s 一轮。 */
    const val POLL_INTERVAL_MS = 60_000L
  }
}
