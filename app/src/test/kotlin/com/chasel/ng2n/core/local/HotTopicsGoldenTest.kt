package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class HotTopicsGoldenTest {

  @Test
  fun `hot-topics 金样本全量对拍`() = runGoldenDomain("hot-topics") {
    fn("aggregateHotTopics") { case ->
      val options = case.field("options").jsonObject
      val now = options.getValue("now").jsonPrimitive.long
      val windowHours = options["windowHours"]?.jsonPrimitive?.long?.toInt() ?: HOT_WINDOW_HOURS
      val pages = case.field("pages").jsonArray.map { page ->
        page.jsonArray.map { Candidate(it.jsonObject) }
      }
      JsonArray(aggregateHotTopics(pages, now, windowHours).map { it.raw })
    }
  }

  @Test
  fun `窗口边界是闭区间的下沿——正好 24h 前发的还算,再早一秒不算`() {
    val now = 1_800_000_000L
    val edge = candidate(tid = 1, postedAt = now - 24 * 3600)
    val outside = candidate(tid = 2, postedAt = now - 24 * 3600 - 1)
    val picked = aggregateHotTopics(listOf(listOf(edge, outside)), now).map { it.tid }
    assertEquals(listOf(1L), picked)
  }

  @Test
  fun `排序的最后一档是 tid 升序,保证结果确定`() {
    val now = 1_800_000_000L
    val a = candidate(tid = 9, replies = 5, lastPostAt = now - 10, postedAt = now - 100)
    val b = candidate(tid = 3, replies = 5, lastPostAt = now - 10, postedAt = now - 100)
    val picked = aggregateHotTopics(listOf(listOf(a, b)), now).map { it.tid }
    assertEquals(listOf(3L, 9L), picked)
  }
}

private class Candidate(val raw: JsonObject) : HotTopicCandidate {
  override val tid = raw.getValue("tid").jsonPrimitive.long
  override val replies = raw.getValue("replies").jsonPrimitive.long
  override val postedAt = raw.getValue("postedAt").jsonPrimitive.long
  override val lastPostAt = raw.getValue("lastPostAt").jsonPrimitive.long

  override val shortcut: Any? = raw["shortcut"]?.takeIf { it !is JsonNull }
  override val jumpUrl: String? = raw["jumpUrl"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
}

private class SimpleCandidate(
  override val tid: Long,
  override val replies: Long,
  override val postedAt: Long,
  override val lastPostAt: Long,
) : HotTopicCandidate

private fun candidate(
  tid: Long,
  replies: Long = 0,
  postedAt: Long,
  lastPostAt: Long = postedAt,
): HotTopicCandidate = SimpleCandidate(tid, replies, postedAt, lastPostAt)
