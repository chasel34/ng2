package com.chasel.ng2n.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubBoardOverrides @Inject constructor() {

  private val state = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  val overrides: StateFlow<Map<String, Boolean>> = state.asStateFlow()

  private val pending = MutableStateFlow<Set<String>>(emptySet())

  val inFlight: StateFlow<Set<String>> = pending.asStateFlow()

  fun keyOf(uid: String, filterId: String): String = "$uid:$filterId"

  fun override(key: String): Boolean? = state.value[key]

  fun beginToggle(key: String, subscribed: Boolean): Boolean? {
    if (key in pending.value) return null
    val previous = state.value[key]
    state.value = state.value + (key to subscribed)
    pending.value = pending.value + key
    return previous
  }

  fun rollback(key: String, previous: Boolean?) {
    state.value = if (previous == null) state.value - key else state.value + (key to previous)
  }

  fun endToggle(key: String) {
    pending.value = pending.value - key
  }
}

@Singleton
class RecommendMarks @Inject constructor() {

  private val marks = HashMap<Long, MutableMap<Long, Mark>>()

  data class Mark(val state: String, val scoreDelta: Int)

  @Synchronized
  fun of(tid: Long, pid: Long): Mark? = marks[tid]?.get(pid)

  @Synchronized
  fun put(tid: Long, pid: Long, mark: Mark) {
    marks.getOrPut(tid) { HashMap() }[pid] = mark
  }

  @Synchronized
  fun forget(tid: Long) {
    marks.remove(tid)
  }

  @Synchronized
  fun clear() {
    marks.clear()
  }
}
