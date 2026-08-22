package com.chasel.ng2n.data.notifications

import com.chasel.ng2n.data.db.NotificationReadDao
import com.chasel.ng2n.data.db.NotificationReadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知已读的仓库。
 *
 * **只存已读 ID,不存条目**:`get_all` 每次都返回近期全量,已读靠稳定 ID 对上号
 * (`notificationId`)。已读按 (uid, id) 分桶 —— 切号后各看各的已读,互不污染。
 *
 * 合并/未读数/分组这些纯规则在 `NotificationPolicy.kt`。
 */
@Singleton
class NotificationReadRepository @Inject constructor(
  private val dao: NotificationReadDao,
) {

  /** 某账号的已读集合。游客态(uid 为 null)恒为空集,不查库。 */
  fun observeReadIds(uid: String?): Flow<Set<String>> =
    if (uid == null) kotlinx.coroutines.flow.flowOf(emptySet())
    else dao.observeReadIds(uid).map { it.toSet() }

  suspend fun readIds(uid: String?): Set<String> =
    if (uid == null) emptySet() else dao.readIds(uid).toSet()

  /**
   * 标记一批 ID 为已读。
   * 只把**这次新读到的**写盘:进页会把整屏条目都报一遍,老 ID 不必反复 INSERT。
   */
  suspend fun markRead(uid: String?, ids: List<String>, nowMs: Long = System.currentTimeMillis()) {
    if (uid == null || ids.isEmpty()) return
    dao.insertAll(ids.distinct().map { NotificationReadEntity(uid, it, nowMs) })
  }

  /** 一键清空:服务端 `del` 成功之后才动本地。 */
  suspend fun clear(uid: String?) {
    if (uid == null) return
    dao.clearForUid(uid)
  }
}
