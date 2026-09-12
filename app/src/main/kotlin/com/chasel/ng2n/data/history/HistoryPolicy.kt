package com.chasel.ng2n.data.history

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 浏览历史与阅读进度的核心模型 —— `src/core/local/history.ts` 的直译。
 *
 * **纯 Kotlin,零 Android 依赖**:LRU/去重/进度前进的规则全在这里,Room 读写归
 * `HistoryRepository`。列表是「新的在前」的有序 List —— 上限只有 200 条,
 * List 比 Map 省事,还天然就是历史页要的展示顺序。
 *
 * 历史与阅读进度是**同一条记录**(browse_history 的一行),不拆两张表。
 */

/** 设计稿历史页副标题:「本机记录 · 保留最近 200 条」。 */
const val HISTORY_LIMIT = 200

/** 历史里的一条主题。 */
data class HistoryEntry(
  val tid: Long,
  val subject: String,
  /** 楼主名(已匿名还原);从非第 1 页进来时可能拿不到 */
  val author: String? = null,
  /** 列表行标题后面那个灰色 `[版块名]` */
  val boardName: String? = null,
  /** fav 码,重新打开隐藏/过期主题时必带 */
  val favCode: String? = null,
  /**
   * 已读到的最高楼层号(`lou`,0 = 主楼)。只前进不后退 ——
   * 重进主题回头翻前几楼不该把「读到 96 楼」倒退回「读到 3 楼」,
   * 「读完」状态也不该因为回头看一眼就丢。
   */
  val lastFloor: Int = 0,
  /** 已知的最高楼层号(= 楼层总数 - 1),用来判断「读完」;有新回复时会涨 */
  val maxFloor: Int = 0,
  /** 最近一次浏览,秒级 unix 时间戳;LRU 淘汰与展示排序都按它 */
  val updatedAt: Long,
)

/** 一次进入主题时上报的资料。楼层进度走 [advanceHistoryFloor],这里只管元数据。 */
data class TopicVisit(
  val tid: Long,
  val subject: String,
  val author: String? = null,
  val boardName: String? = null,
  val favCode: String? = null,
  /** 本次已知的最高楼层号(楼层总数 - 1);拿不到就不更新 */
  val maxFloor: Int? = null,
)

/**
 * 一次变更的结果。[changed] 为 false 时 [entries] 就是原 List(**引用相等**),
 * 仓库据此跳过 Room 写入;[evictedTids] 是被 LRU 挤出去的主题,要连带删行。
 */
data class HistoryUpdate(
  val entries: List<HistoryEntry>,
  val changed: Boolean,
  val evictedTids: List<Long> = emptyList(),
)

private fun unchanged(entries: List<HistoryEntry>) = HistoryUpdate(entries, changed = false)

/**
 * 浏览一个主题:同主题**更新时间与资料而不是新增条目**,条目挪到最前;
 * 新主题插到最前,超过 200 条时把最老的挤出去。
 *
 * 楼层进度按「只前进」合并:`visit.maxFloor` 只会把已知上限往上抬。
 * 元数据(标题/楼主/版块名/fav 码)以新值优先,但新值缺席时保留旧值 ——
 * 从第 2 页直接进来拿不到楼主,不能把第一次记下的名字冲掉。
 */
fun upsertHistory(
  entries: List<HistoryEntry>,
  visit: TopicVisit,
  now: Long,
): HistoryUpdate {
  val existing = entries.firstOrNull { it.tid == visit.tid }

  val next = HistoryEntry(
    tid = visit.tid,
    subject = if (visit.subject != "") visit.subject else (existing?.subject ?: ""),
    lastFloor = existing?.lastFloor ?: 0,
    maxFloor = maxOf(existing?.maxFloor ?: 0, visit.maxFloor ?: 0),
    updatedAt = now,
    author = visit.author ?: existing?.author,
    boardName = visit.boardName ?: existing?.boardName,
    favCode = visit.favCode ?: existing?.favCode,
  )

  val kept = entries.filter { it.tid != visit.tid }
  val capped = listOf(next) + kept
  val evicted = capped.drop(HISTORY_LIMIT)
  return HistoryUpdate(
    entries = capped.take(HISTORY_LIMIT),
    changed = true,
    evictedTids = evicted.map { it.tid },
  )
}

/**
 * 滚动时上报「看到了第 [lou] 楼」。楼层没前进就原样返回(`changed = false`)——
 * 滚动回调触发得很勤,不能每次都去写库;`updatedAt` 的「最近浏览」语义
 * 由进入主题时的 [upsertHistory] 负责,这里只管楼层数字。
 */
fun advanceHistoryFloor(
  entries: List<HistoryEntry>,
  tid: Long,
  lou: Int,
  now: Long,
): HistoryUpdate {
  val existing = entries.firstOrNull { it.tid == tid }
  // 条目必须先由 upsertHistory 建好;还没建就丢弃这次上报,别造出残缺行
  if (existing == null || lou <= existing.lastFloor) return unchanged(entries)

  val next = existing.copy(
    lastFloor = lou,
    // 楼层号只可能落在 0..maxFloor 里;真看到了更大的说明上限过时了,一起抬
    maxFloor = maxOf(existing.maxFloor, lou),
    updatedAt = now,
  )
  return HistoryUpdate(
    // 挪到最前:正在读的就是最近浏览的,内存里的顺序要与重启后按 updated_at
    // 重排的顺序一致,不然历史页在一次会话内外长得不一样
    entries = listOf(next) + entries.filter { it.tid != tid },
    changed = true,
  )
}

/** 是否「读完」:读到了已知的最后一楼。空主题(只有主楼)读过主楼就算读完。 */
fun isHistoryFinished(lastFloor: Int, maxFloor: Int): Boolean = lastFloor >= maxFloor

/** 历史页右侧那格文案:「读完」或「读到 N 楼」。 */
fun historyProgressLabel(lastFloor: Int, maxFloor: Int): String = when {
  isHistoryFinished(lastFloor, maxFloor) -> "读完"
  lastFloor == 0 -> "读到主楼"
  else -> "读到 $lastFloor 楼"
}

/** 第 [lou] 楼落在第几页(页码从 1 起)。主楼 lou=0 在第 1 页。 */
fun pageOfFloor(lou: Int, rowsPerPage: Int): Int {
  val perPage = maxOf(1, rowsPerPage)
  return maxOf(0, lou) / perPage + 1
}

private const val MINUTE = 60L
private const val HOUR = 3600L

/**
 * 历史页时间列的口径(照设计稿示例行:刚刚 / 12 分钟前 / 今天 20:14 /
 * 昨天 23:41 / 前天 / 更早直接给日期)。
 *
 * 一小时以内先走相对时间(跨没跨零点都一样,29 分钟前就是「29 分钟前」);
 * 再往前的「今天/昨天/前天」按**本地日历日**算而不是按 24 小时窗口 —— 凌晨 0 点后,
 * 昨晚 9 点的记录该叫「昨天」而不是「今天」。[now] 由调用方注入,函数本身可单测。
 */
fun formatHistoryTime(
  updatedAt: Long,
  now: Long,
  zone: ZoneId = ZoneId.systemDefault(),
): String {
  val elapsed = now - updatedAt
  if (elapsed < MINUTE) return "刚刚"
  if (elapsed < HOUR) return "${elapsed / MINUTE} 分钟前"

  val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(updatedAt), zone)
  val today = LocalDate.ofInstant(Instant.ofEpochSecond(now), zone)
  val dayDiff = ChronoUnit.DAYS.between(time.toLocalDate(), today)
  return when {
    dayDiff <= 0L -> "今天 ${clockOf(time)}"
    dayDiff == 1L -> "昨天 ${clockOf(time)}"
    dayDiff == 2L -> "前天"
    else -> dateOf(time)
  }
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

private fun clockOf(time: LocalDateTime): String = "${pad2(time.hour)}:${pad2(time.minute)}"

private fun dateOf(time: LocalDateTime): String =
  "${time.year}-${pad2(time.monthValue)}-${pad2(time.dayOfMonth)}"
