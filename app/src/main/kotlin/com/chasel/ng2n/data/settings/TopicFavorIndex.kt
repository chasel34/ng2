package com.chasel.ng2n.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

typealias TopicFavorIndex = Map<Long, List<Int>>

val EMPTY_TOPIC_FAVOR_INDEX: TopicFavorIndex = emptyMap()

private fun normalizeFolderIds(ids: Iterable<Int>): List<Int> =
  ids.toSet().filter { it > 0 }.sorted()

fun foldersOfTopic(index: TopicFavorIndex, tid: Long): List<Int> = index[tid] ?: emptyList()

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
  val favored: Boolean,
)

fun applyFavoriteChange(index: TopicFavorIndex, change: FavoriteChange): TopicFavorIndex {
  val current = foldersOfTopic(index, change.tid)
  val next = if (change.favored) {
    normalizeFolderIds(current + change.folderId)
  } else {
    current.filter { it != change.folderId }
  }
  return withTopic(index, change.tid, next)
}

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

  for ((tid, folderIds) in next.entries.toList()) {
    if (folderId !in folderIds || tid in seen) continue
    next = withTopic(next, tid, folderIds.filter { it != folderId })
  }
  return next
}

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
  val added: List<Int>,
  val removed: List<Int>,
)

fun diffFolderSelection(before: List<Int>, after: List<Int>): FolderSelectionDiff {
  val had = before.toSet()
  val has = after.toSet()
  return FolderSelectionDiff(
    added = normalizeFolderIds(has.filter { it !in had }),
    removed = normalizeFolderIds(had.filter { it !in has }),
  )
}

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

fun TopicFavorIndex.toJson(): JsonObject = JsonObject(
  entries.associate { (tid, folderIds) ->
    tid.toString() to JsonArray(folderIds.map { JsonPrimitive(it) })
  },
)
