package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaEnvelope
import kotlinx.serialization.json.JsonObject

private inline fun approveFakeError(envelope: NgaEnvelope, judge: (JsonObject) -> String?): String? {
  if (envelope.fakeError != null) return null
  val data = envelope.data as? JsonObject ?: return "响应里没有 data"
  return judge(data)
}

fun rejectNonTopicDetail(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  if (listOf("__R", "__T", "__ROWS").any { it in data }) {
    null
  } else {
    "响应里没有帖子详情结构（多半是被限流或拦截了）"
  }
}

fun rejectNonBoardTree(envelope: NgaEnvelope): String? {
  if (envelope.fakeError != null) return null
  val root = envelope.root
  return if ("data" in root || "other" in root) null else "响应不是版块分类树（多半是被限流或拦截了）"
}

fun rejectNonNotificationFeed(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  if ("0" in data) null else "响应里没有通知结构（多半是被限流或拦截了）"
}

fun rejectNonUcpPayload(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  when {
    "0" in data -> null
    data.isEmpty() -> null
    else -> "响应里没有 ucp 结构（多半是被限流或拦截了）"
  }
}

fun rejectNonBoardSearch(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  val looksLikeResults = data.keys.any { key -> key.toLongOrNull() != null }
  if (looksLikeResults || "__MESSAGE" in data) {
    null
  } else {
    "响应里没有版块搜索结果结构（多半是被限流或拦截了）"
  }
}
