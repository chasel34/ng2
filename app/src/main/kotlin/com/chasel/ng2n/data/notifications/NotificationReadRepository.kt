package com.chasel.ng2n.data.notifications

import com.chasel.ng2n.data.db.NotificationReadDao
import com.chasel.ng2n.data.db.NotificationReadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationReadRepository @Inject constructor(
  private val dao: NotificationReadDao,
) {

  fun observeReadIds(uid: String?): Flow<Set<String>> =
    if (uid == null) kotlinx.coroutines.flow.flowOf(emptySet())
    else dao.observeReadIds(uid).map { it.toSet() }

  suspend fun readIds(uid: String?): Set<String> =
    if (uid == null) emptySet() else dao.readIds(uid).toSet()

  suspend fun markRead(uid: String?, ids: List<String>, nowMs: Long = System.currentTimeMillis()) {
    if (uid == null || ids.isEmpty()) return
    dao.insertAll(ids.distinct().map { NotificationReadEntity(uid, it, nowMs) })
  }

  suspend fun clear(uid: String?) {
    if (uid == null) return
    dao.clearForUid(uid)
  }
}
