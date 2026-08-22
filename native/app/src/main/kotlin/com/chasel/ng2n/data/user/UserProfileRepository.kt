package com.chasel.ng2n.data.user

import com.chasel.ng2n.core.api.UserProfile
import com.chasel.ng2n.core.api.fetchUserAvatar
import com.chasel.ng2n.core.api.fetchUserProfile
import com.chasel.ng2n.core.api.updateSignature
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一个人的资料 —— 直译 RN 侧 `src/store/user-profile.ts`。
 *
 * **头像缺失时补一次查询**:`ucp get` 对不少账号的 `avatar` 是空串,但
 * `ucp get_avatar` 还给得出来(API 文档 §11.2)。补查失败不影响这份资料 ——
 * 拿不到就让 UI 走首字占位,不该为一张头像把整页变成错误页。
 *
 * 资料不常变,[STALE_MS] 给足:从楼层反复点进同一个人不该反复打 ucp(ADR-0002)。
 * 按 uid 分桶、桶数有上限([MAX_ENTRIES]),满了丢最久没碰过的 —— 对应 TanStack 的 gcTime。
 */
@Singleton
class UserProfileRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class State(
    val loading: Boolean = false,
    val profile: UserProfile? = null,
    val error: Throwable? = null,
    val fetchedAtMs: Long = 0,
  )

  private val entries = MutableStateFlow<Map<Long, State>>(emptyMap())
  val states: StateFlow<Map<Long, State>> = entries.asStateFlow()

  private val touched = LinkedHashMap<Long, Long>()
  private val locks = HashMap<Long, Mutex>()

  fun stateOf(uid: Long): State = entries.value[uid] ?: State()

  @Synchronized
  private fun lockOf(uid: Long): Mutex = locks.getOrPut(uid) { Mutex() }

  private fun put(uid: Long, transform: (State) -> State) {
    var next = entries.value + (uid to transform(entries.value[uid] ?: State()))
    touched[uid] = System.nanoTime()
    if (next.size > MAX_ENTRIES) {
      val victim = touched.entries.filter { it.key in next.keys }.minByOrNull { it.value }?.key
      if (victim != null && victim != uid) {
        next = next - victim
        touched.remove(victim)
        synchronized(this) { locks.remove(victim) }
      }
    }
    entries.value = next
  }

  /** 进屏时调。**幂等**:[STALE_MS] 内不重问。 */
  suspend fun ensureLoaded(uid: Long, nowMs: Long = System.currentTimeMillis()) {
    // RN 侧 `enabled: Number.isFinite(uid) && uid > 0`
    if (uid <= 0) return
    val current = stateOf(uid)
    if (current.profile != null && nowMs - current.fetchedAtMs < STALE_MS) {
      put(uid) { it } // 只是挪到 LRU 的近端
      return
    }
    load(uid, nowMs)
  }

  /** 「重试」/ 改完签名之后的回读。无条件重取。 */
  suspend fun reload(uid: Long, nowMs: Long = System.currentTimeMillis()) {
    if (uid <= 0) return
    load(uid, nowMs)
  }

  private suspend fun load(uid: Long, nowMs: Long) = lockOf(uid).withLock {
    put(uid) { it.copy(loading = true, error = null) }
    try {
      val profile = fetchUserProfile(client, uid)
      val withAvatar = if (profile.avatarUrl != null) {
        profile
      } else {
        // 补查头像:失败就算了,不该为一张头像把整页变成错误页
        val url = try {
          fetchUserAvatar(client, uid)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          null
        }
        if (url == null) profile else profile.copy(avatarUrl = url)
      }
      put(uid) { it.copy(loading = false, profile = withAvatar, error = null, fetchedAtMs = nowMs) }
    } catch (cancelled: CancellationException) {
      put(uid) { it.copy(loading = false) }
      throw cancelled
    } catch (error: Throwable) {
      put(uid) { it.copy(loading = false, error = error) }
    }
  }

  /**
   * 改自己的签名(API 文档 §11.3)。写完**重拉一次资料**而不是就地改缓存:
   * 只有服务端存下来的那一份说得算(提交要过实体转义,存进去与读回来是否对得上
   * 正是这里要验的东西)。
   *
   * 只能改自己的 —— 服务端认 cookie 里的账号,入口由 UI 挡住(资料页只对当前账号显示编辑)。
   */
  suspend fun saveSignature(uid: String, signature: String) {
    updateSignature(client, uid, signature)
    uid.toLongOrNull()?.let { reload(it) }
  }

  private companion object {
    /** RN 侧 `staleTime: 5 * 60 * 1000`。 */
    const val STALE_MS = 5 * 60 * 1000L
    const val MAX_ENTRIES = 8
  }
}
