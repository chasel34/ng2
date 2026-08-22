package com.chasel.ng2n.data.notifications

import com.chasel.ng2n.core.api.NgaNotification
import com.chasel.ng2n.core.api.clearNotificationFeed
import com.chasel.ng2n.core.api.fetchNotificationFeed
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.settings.SettingsStore
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

  /**
   * 一次拉取在不在途(通知屏空态转圈用)。**票 17a 补** —— RN 侧
   * `store/notifications.ts` 的 `refreshing` 字段;票 14 只搬了轮询那一半。
   */
  private val inFlight = MutableStateFlow(false)
  val refreshing: StateFlow<Boolean> = inFlight.asStateFlow()

  /**
   * 条目归属哪个账号。**票 17a 补**:RN 侧 `activate(uid)` 在切号时把条目清空
   * 并换已读桶;票 14 这版每轮现读 uid,却从不清条目 —— 切号之后上一个账号的
   * 通知会留在列表里,未读数也跟着串(与 P1-02 同一类问题)。
   */
  private var activeUid: String? = null

  private var job: Job? = null
  private var subscribers = 0

  /**
   * 退避状态。RN 侧 `POLL_BACKOFF_SKIPS`:定时器仍是稳稳的 60s 一格,
   * 只是连续拉空时到点了不一定发请求(前台常驻每分钟一发 `nuke.php`,
   * 和用户自己的浏览抢同一份服务端配额,ADR-0002)。
   */
  private var idleRounds = 0
  private var skipRounds = 0

  /**
   * 前台开始轮询。可重入:多个屏幕同时订阅只跑一条循环。
   * **刚进前台等于「用户现在就想看」**:退避清零,先拉一发(RN 侧同款)。
   */
  @Synchronized
  fun start() {
    subscribers += 1
    if (job?.isActive == true) return
    idleRounds = 0
    skipRounds = 0
    job = scope.launch {
      while (isActive) {
        tick()
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

  /** 手动拉一次(通知屏进页与下拉刷新)。不吃退避 —— 那是用户主动看的。 */
  suspend fun refresh() = pollOnce()

  /** 轮询的一格:该跳就跳,拉到新东西就把退避清零。 */
  private suspend fun tick() {
    // 「被喷提示」关掉的那一档要的正是「别再来打扰」:不轮询、角标恒 0。
    // 通知屏自己进去还是会拉([refresh]),那是用户主动看的
    if (!settings.currentSettings().sprayNotice) {
      unreadCount.value = 0
      return
    }
    if (skipRounds > 0) {
      skipRounds -= 1
      return
    }
    val before = items.value.size
    pollOnce()
    // mergeNotifications 只增不覆盖,所以条数变多就是真拉到了新东西
    val fresh = items.value.size > before
    idleRounds = if (fresh) 0 else minOf(idleRounds + 1, POLL_BACKOFF_SKIPS.size - 1)
    skipRounds = POLL_BACKOFF_SKIPS[idleRounds]
  }

  private suspend fun pollOnce() {
    val uid = currentAccountOf(accounts.accounts.first())?.uid
    // 切号 / 登出:条目与未读一起清掉,已读桶跟着换(readRepository 按 uid 查)
    if (uid != activeUid) {
      activeUid = uid
      items.value = emptyList()
      unreadCount.value = 0
      failure.value = null
    }
    if (uid == null) {
      // 游客态:接口会报「你必须先登录论坛」,不发请求,角标归零
      return
    }
    // 同一时刻只跑一发:轮询的一格与用户的下拉刷新撞上时,后到的那个直接让路
    if (inFlight.value) return
    inFlight.value = true
    try {
      val feed = fetchNotificationFeed(client)
      // 拉取期间可能切了号:结果归属旧账号就整个丢弃
      if (activeUid != uid) return
      val merged = mergeNotifications(items.value.map(::Wrapped), feed.items.map(::Wrapped))
      items.value = merged.map { it.item }
      unreadCount.value = unreadCount(merged, readRepository.readIds(uid))
      failure.value = null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      // 轮询失败不打扰用户:角标维持上一次的数,原因留给通知屏去说
      if (activeUid == uid) failure.value = error
    } finally {
      inFlight.value = false
    }
  }

  /**
   * 一键清空:服务端 `del` 成功之后才动本地(条目 + 已读桶一起清)。
   * 失败原样抛给调用方去说 —— 通知屏要把服务端那句话带出来。
   */
  suspend fun clearAll() {
    val uid = currentAccountOf(accounts.accounts.first())?.uid ?: return
    clearNotificationFeed(client)
    if (activeUid != uid) return
    items.value = emptyList()
    unreadCount.value = 0
    failure.value = null
    readRepository.clear(uid)
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

    /**
     * 一直没有新通知时,跳过几格再拉。索引 = 连续拉空的次数,
     * 于是实际间隔是 60s → 120s → 180s → 300s(RN 侧 `POLL_BACKOFF_SKIPS` 同值)。
     */
    val POLL_BACKOFF_SKIPS = intArrayOf(0, 1, 2, 4)
  }
}
