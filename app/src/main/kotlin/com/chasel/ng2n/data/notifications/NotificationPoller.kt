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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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

  private val inFlight = MutableStateFlow(false)
  val refreshing: StateFlow<Boolean> = inFlight.asStateFlow()

  private var activeUid: String? = null

  private var job: Job? = null
  private var subscribers = 0

  private var idleRounds = 0
  private var skipRounds = 0

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

  @Synchronized
  fun stop() {
    subscribers = (subscribers - 1).coerceAtLeast(0)
    if (subscribers > 0) return
    job?.cancel()
    job = null
  }

  suspend fun refresh() = withContext(Dispatchers.IO) { pollOnce() }

  private suspend fun tick() {
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
    val fresh = items.value.size > before
    idleRounds = if (fresh) 0 else minOf(idleRounds + 1, POLL_BACKOFF_SKIPS.size - 1)
    skipRounds = POLL_BACKOFF_SKIPS[idleRounds]
  }

  private suspend fun pollOnce() {
    val uid = currentAccountOf(accounts.accounts.first())?.uid
    if (uid != activeUid) {
      activeUid = uid
      items.value = emptyList()
      unreadCount.value = 0
      failure.value = null
    }
    if (uid == null) {
      return
    }
    if (inFlight.value) return
    inFlight.value = true
    try {
      val feed = fetchNotificationFeed(client)
      if (activeUid != uid) return
      val merged = mergeNotifications(items.value.map(::Wrapped), feed.items.map(::Wrapped))
      items.value = merged.map { it.item }
      unreadCount.value = unreadCount(merged, readRepository.readIds(uid))
      failure.value = null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      if (activeUid == uid) failure.value = error
    } finally {
      inFlight.value = false
    }
  }

  suspend fun clearAll() {
    val uid = currentAccountOf(accounts.accounts.first())?.uid ?: return
    withContext(Dispatchers.IO) {
      clearNotificationFeed(client)
      if (activeUid != uid) return@withContext
      items.value = emptyList()
      unreadCount.value = 0
      failure.value = null
      readRepository.clear(uid)
    }
  }

  suspend fun markRead(ids: List<String>) {
    val uid = currentAccountOf(accounts.accounts.first())?.uid ?: return
    withContext(Dispatchers.IO) {
      readRepository.markRead(uid, ids)
      unreadCount.value = unreadCount(items.value.map(::Wrapped), readRepository.readIds(uid))
    }
  }

  private data class Wrapped(val item: NgaNotification) : NotificationLike {
    override val id: String get() = item.id
    override val timestamp: Long get() = item.timestamp
  }

  private companion object {
    const val POLL_INTERVAL_MS = 60_000L

    val POLL_BACKOFF_SKIPS = intArrayOf(0, 1, 2, 4)
  }
}
