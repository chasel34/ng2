package com.chasel.ng2n.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 「这个主题在哪几个收藏夹里」的本机索引 —— `src/core/local/topic-favor-index.ts` 的直译。
 *
 * **为什么要本机记**:`topic_favor_v2` 只给得出「某个夹里有哪些主题」
 * (`thread.php?favor=<夹id>`),给不出反向的「某个主题在哪几个夹里」——
 * MNGA 与官方 Android 端也都没有这个接口。而多选收藏对话框必须先知道当前勾了哪几个夹,
 * 才谈得上「取消单夹不影响其他夹」。
 *
 * 逐夹翻页去反查是不行的:一个夹上百个主题就是好几页,20 个夹就是几十个请求,
 * 正撞在 NGA 封第三方客户端的枪口上(ADR-0002)。所以改成**只记本机看得见的那部分**:
 *
 * - 收藏/取消收藏成功后,按结果改索引(这条最准,是用户刚做的事);
 * - 打开某个收藏夹的主题列表时,把那一页的 tid 一并记下([seedFolderTopics])。
 *
 * 于是索引是「宁缺勿滥」的:记着的一定对,没记着的**未必**没收藏。
 *
 * **修 P1-02 的存储侧**:索引按 uid 分键落盘(`topic-favor-index/v1/<uid>`,与 RN 版同),
 * 游客态恒为空索引。RN 版这一层本来就按 uid 分了,漏的是 TanStack Query 的 key —— 那半
 * 归票 16/17 的仓库层,这里只保证存储不串号。
 */

/** tid → 该主题所属的收藏夹 id(本机已知的那部分),夹 id 升序且不重复。 */
typealias TopicFavorIndex = Map<Long, List<Int>>

val EMPTY_TOPIC_FAVOR_INDEX: TopicFavorIndex = emptyMap()

/** 夹 id 去重升序 —— 索引里存的顺序固定,比较两份索引才有意义。 */
private fun normalizeFolderIds(ids: Iterable<Int>): List<Int> =
  ids.toSet().filter { it > 0 }.sorted()

/** 本机已知的、这个主题所属的收藏夹 id。没记录过就是空 List。 */
fun foldersOfTopic(index: TopicFavorIndex, tid: Long): List<Int> = index[tid] ?: emptyList()

/** 写一条 tid 的归属;夹列表空了就把这条删掉,免得索引里攒一堆空数组。 */
private fun withTopic(index: TopicFavorIndex, tid: Long, folderIds: List<Int>): TopicFavorIndex {
  if (folderIds.isEmpty()) {
    if (tid !in index) return index
    return index - tid
  }
  return index + (tid to folderIds)
}

data class FavoriteChange(
  val tid: Long,
  val folderId: Int,
  /** true = 刚加进这个夹,false = 刚移出 */
  val favored: Boolean,
)

/** 一次收藏/取消收藏落到索引上。服务端已经确认过了才调,所以直接覆盖。 */
fun applyFavoriteChange(index: TopicFavorIndex, change: FavoriteChange): TopicFavorIndex {
  val current = foldersOfTopic(index, change.tid)
  val next = if (change.favored) {
    normalizeFolderIds(current + change.folderId)
  } else {
    current.filter { it != change.folderId }
  }
  return withTopic(index, change.tid, next)
}

/**
 * 拿某个收藏夹的主题列表喂索引。
 *
 * 默认只做加法:只翻了一页的话,没出现在这页里的主题可能在后面几页,不能当成「不在这个夹」。
 * [complete] 为真(整个夹就这一页)时才连带清掉本机记错的归属。
 */
fun seedFolderTopics(
  index: TopicFavorIndex,
  folderId: Int,
  tids: List<Long>,
  complete: Boolean = false,
): TopicFavorIndex {
  val seen = tids.toSet()
  var next = index

  for (tid in seen) {
    next = applyFavoriteChange(next, FavoriteChange(tid, folderId, favored = true))
  }
  if (!complete) return next

  // 整个夹都在手上了:本机记着属于这个夹、却没出现在列表里的,是过期记录
  for ((tid, folderIds) in next.entries.toList()) {
    if (folderId !in folderIds || tid in seen) continue
    next = withTopic(next, tid, folderIds.filter { it != folderId })
  }
  return next
}

/**
 * 删掉已经不存在的收藏夹留下的归属记录(用户删夹之后调)。
 * 传的是服务端最新的夹 id 全集。
 */
fun pruneFolders(index: TopicFavorIndex, existingFolderIds: Iterable<Int>): TopicFavorIndex {
  val alive = existingFolderIds.toSet()
  var next = index
  for ((tid, folderIds) in index) {
    val kept = folderIds.filter { it in alive }
    if (kept.size != folderIds.size) next = withTopic(next, tid, kept)
  }
  return next
}

data class FolderSelectionDiff(
  /** 要调 `add` 的夹 */
  val added: List<Int>,
  /** 要调 `del` 的夹 */
  val removed: List<Int>,
)

/**
 * 对话框点「完成」时,把勾选前后的差算出来 —— 只对改动过的夹发请求,
 * 没动过的夹一个请求都不发(重复 add 会把 `length` 算重)。
 */
fun diffFolderSelection(before: List<Int>, after: List<Int>): FolderSelectionDiff {
  val had = before.toSet()
  val has = after.toSet()
  return FolderSelectionDiff(
    added = normalizeFolderIds(has.filter { it !in had }),
    removed = normalizeFolderIds(had.filter { it !in has }),
  )
}

/**
 * 从落盘的 JSON 还原索引。存储里的东西一律当外部输入校验:
 * 可能是别的版本的 app 写下的,**坏条目跳过而不是整份丢掉**。
 */
fun parseTopicFavorIndex(raw: JsonElement?): TopicFavorIndex {
  val obj = raw as? JsonObject ?: return EMPTY_TOPIC_FAVOR_INDEX
  val index = LinkedHashMap<Long, List<Int>>()
  for ((key, value) in obj) {
    val tid = key.toLongOrNull() ?: continue
    if (tid <= 0) continue
    val array = value as? JsonArray ?: continue
    val folderIds = normalizeFolderIds(
      array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull },
    )
    if (folderIds.isNotEmpty()) index[tid] = folderIds
  }
  return index
}

/** 落盘形态:`{ "<tid>": [夹 id…] }`,与 RN 版 `topic-favor-index/v1/<uid>` 同构。 */
fun TopicFavorIndex.toJson(): JsonObject = JsonObject(
  entries.associate { (tid, folderIds) ->
    tid.toString() to JsonArray(folderIds.map { JsonPrimitive(it) })
  },
)
