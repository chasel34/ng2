package com.chasel.ng2n.data.user

import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicList
import com.chasel.ng2n.core.api.UserPostKind
import com.chasel.ng2n.core.api.fetchUserTopics
import com.chasel.ng2n.core.api.hasMoreUserPosts
import com.chasel.ng2n.core.api.mergeUserPostPages
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
class UserPostsRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class Key(val uid: Long, val kind: UserPostKind)

  data class State(
    val loading: Boolean = true,
    val pages: List<TopicList> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val error: Throwable? = null,
    val loadingNextPage: Boolean = false,
    val refreshing: Boolean = false,
    val hasNextPage: Boolean = true,
  )

  private val entries = MutableStateFlow<Map<Key, State>>(emptyMap())
  val states: StateFlow<Map<Key, State>> = entries.asStateFlow()

  private val touched = LinkedHashMap<Key, Long>()
  private val locks = HashMap<Key, Mutex>()

  fun stateOf(key: Key): State = entries.value[key] ?: State()

  @Synchronized
  private fun lockOf(key: Key): Mutex = locks.getOrPut(key) { Mutex() }

  private fun put(key: Key, transform: (State) -> State) {
    var next = entries.value + (key to transform(entries.value[key] ?: State()))
    touched[key] = System.nanoTime()
    if (next.size > MAX_ENTRIES) {
      val victim = touched.entries.filter { it.key in next.keys }.minByOrNull { it.value }?.key
      if (victim != null && victim != key) {
        next = next - victim
        touched.remove(victim)
        synchronized(this) { locks.remove(victim) }
      }
    }
    entries.value = next
  }

  suspend fun ensureFirstPage(key: Key) {
    if (key.uid <= 0) return
    if (entries.value[key]?.pages?.isNotEmpty() == true) {
      put(key) { it }
      return
    }
    lockOf(key).withLock {
      if (entries.value[key]?.pages?.isNotEmpty() == true) return@withLock
      put(key) { it.copy(loading = true, error = null) }
      fetchInto(key, page = 1, replace = true)
    }
  }

  suspend fun refresh(key: Key) = lockOf(key).withLock {
    put(key) { it.copy(refreshing = true, error = null) }
    fetchInto(key, page = 1, replace = true)
    put(key) { it.copy(refreshing = false) }
  }

  suspend fun retry(key: Key) = lockOf(key).withLock {
    client.forgetSuccessfulCombo("thread.php")
    put(key) { State(loading = true) }
    fetchInto(key, page = 1, replace = true)
  }

  suspend fun loadNextPage(key: Key) {
    val current = stateOf(key)
    if (!current.hasNextPage || current.loadingNextPage || current.loading) return
    lockOf(key).withLock {
      val now = stateOf(key)
      if (!now.hasNextPage || now.loadingNextPage) return@withLock
      put(key) { it.copy(loadingNextPage = true, error = null) }
      fetchInto(key, page = now.pages.size + 1, replace = false)
      put(key) { it.copy(loadingNextPage = false) }
    }
  }

  private suspend fun fetchInto(key: Key, page: Int, replace: Boolean) = withContext(Dispatchers.IO) {
    try {
      val fetched = fetchUserTopics(client, uid = key.uid, kind = key.kind, page = page)
      put(key) { state ->
        val pages = if (replace) listOf(fetched) else state.pages + fetched
        state.copy(
          loading = false,
          pages = pages,
          topics = mergeUserPostPages(pages),
          error = null,
          hasNextPage = hasMoreUserPosts(fetched),
        )
      }
    } catch (cancelled: CancellationException) {
      put(key) { it.copy(loading = false, loadingNextPage = false, refreshing = false) }
      throw cancelled
    } catch (error: Throwable) {
      put(key) { it.copy(loading = false, error = error) }
    }
  }

  private companion object {
    const val MAX_ENTRIES = 4
  }
}
