package com.chasel.ng2n.data.user

import com.chasel.ng2n.core.api.UserProfile
import com.chasel.ng2n.core.api.fetchUserAvatar
import com.chasel.ng2n.core.api.fetchUserProfile
import com.chasel.ng2n.core.api.updateSignature
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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

  suspend fun ensureLoaded(uid: Long, nowMs: Long = System.currentTimeMillis()) {
    if (uid <= 0) return
    val current = stateOf(uid)
    if (current.profile != null && nowMs - current.fetchedAtMs < STALE_MS) {
      put(uid) { it }
      return
    }
    load(uid, nowMs)
  }

  suspend fun reload(uid: Long, nowMs: Long = System.currentTimeMillis()) {
    if (uid <= 0) return
    load(uid, nowMs)
  }

  private suspend fun load(uid: Long, nowMs: Long) = withContext(Dispatchers.IO) {
    lockOf(uid).withLock {
      put(uid) { it.copy(loading = true, error = null) }
      try {
        val profile = fetchUserProfile(client, uid)
        val withAvatar = if (profile.avatarUrl != null) {
          profile
        } else {
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
  }

  suspend fun saveSignature(uid: String, signature: String) {
    withContext(Dispatchers.IO) {
      updateSignature(client, uid, signature)
      uid.toLongOrNull()?.let { reload(it) }
    }
  }

  private companion object {
    const val STALE_MS = 5 * 60 * 1000L
    const val MAX_ENTRIES = 8
  }
}
