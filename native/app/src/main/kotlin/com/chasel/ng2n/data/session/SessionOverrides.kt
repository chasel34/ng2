package com.chasel.ng2n.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 两个**刻意不持久化**的会话级数据集(research/inventory.md §7 明文点名)。
 *
 * 票 14 只提供内存 holder,不给它们任何落盘的口子。原因逐条写在下面 ——
 * 这不是「还没做」,是「做了会错」。
 */

/**
 * 子版块订阅/屏蔽的本地覆盖。
 *
 * **为什么不持久化**:服务端**不回新的 attributes**,只回一句「操作成功」;
 * 而 attributes 是随主题列表(`thread.php` 的 `__F.sub_forums`)一起下来的,
 * 重拉一次列表只为看一个开关太贵(ADR-0002)。所以改过的状态记在内存里,
 * 盖在解析出来的 attributes 上:显示 = 本地改动 ?? 魔法数判定。
 *
 * 落盘的话就麻烦了:用户在网页版或别的客户端改了订阅,本机这份过期覆盖会**永远**
 * 盖住服务端的真值,而且没有任何过期机制能把它摘掉。会话级则天然自愈 ——
 * 下次冷启动重拉列表,回到服务端口径。
 *
 * 按账号分桶:切号后订阅关系不是同一份。
 */
@Singleton
class SubBoardOverrides @Inject constructor() {

  private val state = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  /** key = `<uid>:<filterId>`。 */
  val overrides: StateFlow<Map<String, Boolean>> = state.asStateFlow()

  private val pending = MutableStateFlow<Set<String>>(emptySet())

  /** 在途的操作,挡住同一个子版块的重复点击。 */
  val inFlight: StateFlow<Set<String>> = pending.asStateFlow()

  fun keyOf(uid: String, filterId: String): String = "$uid:$filterId"

  fun override(key: String): Boolean? = state.value[key]

  fun beginToggle(key: String, subscribed: Boolean): Boolean? {
    if (key in pending.value) return null
    val previous = state.value[key]
    // 乐观切换:开关点了就该立刻动,失败再回滚
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

/**
 * 楼层点赞/点踩的本地标记。
 *
 * **为什么不持久化**:服务端**不下发「我赞过没有」**,状态只能从本会话的操作里长出来。
 * 落盘等于把一份永远无法与服务端对账的影子状态留在本机 —— 用户在网页版点了赞、
 * 或者赞被服务端撤了,本机这条记录会一直错下去,而且越攒越多(一个主题几百楼)。
 *
 * 生命周期跟着**一个主题**走:翻页 / 只看此人期间都还在,离开主题即弃。
 */
@Singleton
class RecommendMarks @Inject constructor() {

  private val marks = HashMap<Long, MutableMap<Long, Mark>>()

  /** 一个楼层的赞踩状态与它带来的分数偏移。 */
  data class Mark(val state: String, val scoreDelta: Int)

  @Synchronized
  fun of(tid: Long, pid: Long): Mark? = marks[tid]?.get(pid)

  @Synchronized
  fun put(tid: Long, pid: Long, mark: Mark) {
    marks.getOrPut(tid) { HashMap() }[pid] = mark
  }

  /** 离开主题时调:这一份就该没了。 */
  @Synchronized
  fun forget(tid: Long) {
    marks.remove(tid)
  }

  @Synchronized
  fun clear() {
    marks.clear()
  }
}
