package com.chasel.ng2n.data.ai

import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.*
import com.chasel.ng2n.core.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForumToolSessionTest {
  private val initial = TopicContext(emptyList(), null, 0, emptyList())
  private fun page() = TopicDetail(tid = 10, subject = "主题", attachBase = "", totalPages = 3,
    floors = listOf(Floor(pid = 20, lou = 1, authorId = -1, authorKey = "a", content = "六字匿名假名[img]https://example.org/a.jpg[/img]")),
    users = mapOf("a" to FloorUser(key = "a", name = "六字匿名假名", rawName = "#anony_test", anonymous = true)))

  @Test fun dozensOfReadsAreSpacedAndCancellationCancelsQueueAndActiveRead() = runTest {
    val limiter = ForumReadLimiter(800) { testScheduler.currentTime }
    val starts = mutableListOf<Long>()
    coroutineScope { repeat(40) { launch { limiter.read { starts += testScheduler.currentTime } } } }
    assertEquals((0 until 40).map { it * 800L }, starts)
    var cancelled = false
    val parent = launch {
      launch { limiter.read { try { awaitCancellation() } finally { cancelled = true } } }
      repeat(30) { launch { limiter.read { fail("Queued request executed") } } }
    }
    advanceTimeBy(800); runCurrent()
    parent.cancelAndJoin(); advanceUntilIdle()
    assertTrue(cancelled)
    assertEquals(40, starts.size)
  }
  @Test fun invalidParametersAreRejectedBeforeReadAndFailuresAreDistinct() = runTest {
    var reads = 0
    val session = ForumToolSession(initial, { reads++; page() }, { emptyList() })
    assertEquals("invalid_parameters", session.execute("read_floor", ForumToolArgs(tid = 10))["status"]?.jsonPrimitive?.content)
    assertEquals("invalid_parameters", session.execute("read_topic_page", ForumToolArgs(tid = -1))["status"]?.jsonPrimitive?.content)
    assertEquals(0, reads)
    assertEquals("permission_denied", ForumToolSession.classify(NgaError(NgaErrorKind.SERVER, "没有权限"))["status"]?.jsonPrimitive?.content)
    assertEquals("unavailable", ForumToolSession.classify(NgaError(NgaErrorKind.SERVER, "主题已删除"))["status"]?.jsonPrimitive?.content)
    assertEquals("request_failed", ForumToolSession.classify(NgaError(NgaErrorKind.NETWORK, "offline"))["status"]?.jsonPrimitive?.content)
  }
  @Test fun reuseAnonymizationImagesAndTextOnlyPolicy() = runTest {
    var reads = 0
    var downloads = 0
    val session = ForumToolSession(initial, { reads++; page() }, { emptyList() },
      imageReader = { downloads++; "data:image/jpeg;base64,YQ==" }, limiter = ForumReadLimiter(0))
    val args = ForumToolArgs(tid = 10, pid = 20)
    val first = session.execute("read_floor", args)
    assertTrue(first.toString().contains("匿名用户"))
    assertFalse(first.toString().contains("六字匿名假名"))
    assertEquals(true, session.execute("read_floor", args)["cached"]?.jsonPrimitive?.boolean)
    val listed = session.execute("list_images", args)
    val id = listed["images"]!!.jsonArray.first().jsonObject["imageId"]!!.jsonPrimitive.content
    repeat(2) {
      val result = session.execute("read_image", ForumToolArgs(imageId = id))
      assertEquals("ok", result["status"]?.jsonPrimitive?.content)
      assertFalse(result.toString().contains("base64"))
      assertEquals("YQ==", session.drainImages().single().input.substringAfter(','))
    }
    assertEquals(1, downloads)
    assertEquals(1, reads)
    val personal = ForumToolSession(initial, { fail("Should not read"); page() }, { emptyList() }, allowImages = false)
    assertEquals("disabled", personal.execute("read_image", ForumToolArgs(imageId = id))["status"]?.jsonPrimitive?.content)
  }
  @Test fun filteringCountsBlockedFloorsAndDoesNotLeakImages() = runTest {
    val session = ForumToolSession(initial, { page() }, { listOf(FilterRule("block", FilterRuleKind.KEYWORD, FilterRuleOrigin.LOCAL, "六字", false)) }, limiter = ForumReadLimiter(0))
    val result = session.execute("list_images", ForumToolArgs(tid = 10))
    assertEquals(1, result["blocked"]?.jsonPrimitive?.int)
    assertEquals(0, result["images"]?.jsonArray?.size)
    assertTrue(session.allSources.isEmpty())
  }
  @Test fun longTextAndImageIndexesArePagedAndFloorReusesLoadedPage() = runTest {
    var reads = 0
    val longPage = page().copy(floors = listOf(page().floors.single().copy(content = "正文".repeat(10000) +
      (1..105).joinToString("") { "[img]https://example.org/$it.jpg[/img]" })))
    val session = ForumToolSession(initial, { reads++; longPage }, { emptyList() }, limiter = ForumReadLimiter(0))
    val first = session.execute("read_topic_page", ForumToolArgs(tid = 10))
    assertEquals(16000, first["material"]!!.jsonPrimitive.content.length)
    assertEquals(16000, first["nextOffset"]!!.jsonPrimitive.int)
    val rest = session.execute("read_topic_page", ForumToolArgs(tid = 10, offset = 16000))
    assertFalse(rest.containsKey("nextOffset"))
    val floor = session.execute("read_floor", ForumToolArgs(tid = 10, pid = 20))
    assertTrue(floor["cached"]!!.jsonPrimitive.boolean)
    val images = session.execute("list_images", ForumToolArgs(tid = 10))
    assertEquals(100, images["images"]!!.jsonArray.size)
    assertEquals(100, images["nextOffset"]!!.jsonPrimitive.int)
    assertEquals(5, session.execute("list_images", ForumToolArgs(tid = 10, offset = 100))["images"]!!.jsonArray.size)
    assertEquals(1, reads)
  }
  @Test fun chainReportsPartialFailureAndFiltersCachedEvidenceOnEveryRead() = runTest {
    var blocked = false
    val reference = page().copy(floors = listOf(page().floors.single().copy(content = "[quote][pid=30,10,2]Reply[/pid]前情[/quote]发言")))
    val session = ForumToolSession(initial, { params -> if (params.pid == 30L) throw NgaError(NgaErrorKind.NETWORK, "offline") else reference },
      { if (blocked) listOf(FilterRule("b", FilterRuleKind.KEYWORD, FilterRuleOrigin.OFFICIAL, "发言", false)) else emptyList() }, limiter = ForumReadLimiter(0))
    val result = session.execute("read_reply_chain", ForumToolArgs(tid = 10, pid = 20))
    assertEquals("partial", result["status"]!!.jsonPrimitive.content)
    assertTrue(result["missing"]!!.jsonArray.isNotEmpty())
    blocked = true
    val filtered = session.execute("read_floor", ForumToolArgs(tid = 10, pid = 20))
    assertEquals(1, filtered["blocked"]!!.jsonPrimitive.int)
    assertFalse(filtered["material"]!!.jsonPrimitive.content.contains("发言"))
  }

  private fun chainFloor(pid: Long, refs: List<Pair<Long, Long>> = emptyList()) = Floor(
    pid = pid, lou = pid, authorKey = "a", content = "发言 $pid" + refs.joinToString("") { (tid, target) ->
      "[quote][pid=$target,$tid,1]引用[/pid]前情[/quote]"
    })

  private fun chainCoordinates(result: JsonObject): List<Pair<Long, Long>> =
    Regex("来源 s[0-9]+ tid=([0-9]+) pid=([0-9]+)")
      .findAll(result.getValue("material").jsonPrimitive.content)
      .map { it.groupValues[1].toLong() to it.groupValues[2].toLong() }.toList()

  @Test fun hotReplyTargetTraversesUpstreamAndDeduplicatesCachedCopies() = runTest {
    var reads = 0
    val hot = chainFloor(20, listOf(10L to 30L))
    val initialPage = page().copy(floors = emptyList(), hotReplies = listOf(hot))
    val session = ForumToolSession(initial, { params ->
      reads++
      assertEquals(30L, params.pid)
      page().copy(floors = listOf(chainFloor(30)))
    }, { emptyList() }, limiter = ForumReadLimiter(0))
    session.seed(com.chasel.ng2n.data.topic.TopicPageParams(10, 1), initialPage)
    session.seed(com.chasel.ng2n.data.topic.TopicPageParams(10, 2), initialPage.copy(page = 2))
    val result = session.execute("read_reply_chain", ForumToolArgs(tid = 10, pid = 20))
    assertEquals("ok", result.getValue("status").jsonPrimitive.content)
    assertEquals(listOf(10L to 20L, 10L to 30L), chainCoordinates(result))
    assertEquals(1, reads)
  }

  @Test fun cachedHotRepliesAreIncludedAsDownstreamExactlyOnce() = runTest {
    val root = chainFloor(20)
    val hot = chainFloor(30, listOf(10L to 20L))
    val session = ForumToolSession(initial, { error("All floors are already cached") }, { emptyList() })
    session.seed(com.chasel.ng2n.data.topic.TopicPageParams(10, 1), page().copy(floors = listOf(root), hotReplies = listOf(hot)))
    session.seed(com.chasel.ng2n.data.topic.TopicPageParams(10, 2), page().copy(page = 2, floors = listOf(hot), hotReplies = listOf(hot)))
    val result = session.execute("read_reply_chain", ForumToolArgs(tid = 10, pid = 20))
    assertEquals(listOf(10L to 20L, 10L to 30L), chainCoordinates(result))
    assertFalse(result.containsKey("nextOffset"))
  }

  @Test fun continuationsBeyondThreeHundredPreserveEveryCrossTopicBranch() = runTest {
    val graph = buildMap {
      for (pid in 1L..300L) put(10L to pid, chainFloor(pid,
        if (pid < 300) listOf(10L to pid + 1) else listOf(20L to 1L, 30L to 1L)))
      for (tid in listOf(20L, 30L)) for (pid in 1L..165L)
        put(tid to pid, chainFloor(pid, if (pid < 165) listOf(tid to pid + 1) else emptyList()))
    }
    var reads = 0
    val session = ForumToolSession(initial, { params ->
      reads++
      page().copy(tid = params.tid, floors = listOf(graph.getValue(params.tid to checkNotNull(params.pid))))
    }, { emptyList() }, limiter = ForumReadLimiter(0))
    var args = ForumToolArgs(tid = 10, pid = 1)
    val received = mutableListOf<Pair<Long, Long>>()
    var limitCount = 0
    var requests = 0
    var firstContinuation: ForumToolArgs? = null
    var firstContinuedPage: List<Pair<Long, Long>>? = null
    while (true) {
      assertTrue(++requests <= 40)
      val result = session.execute("read_reply_chain", args)
      assertEquals("ok", result.getValue("status").jsonPrimitive.content)
      val coordinates = chainCoordinates(result)
      assertTrue(coordinates.size in 1..20)
      received += coordinates
      if (args == firstContinuation) firstContinuedPage = coordinates
      val next = result["continuation"]?.jsonObject
      args = when {
        next != null -> {
          if (result["rangeLimitReached"]?.jsonPrimitive?.boolean == true) limitCount++
          assertFalse(result.containsKey("nextOffset"))
          Json.decodeFromJsonElement<ForumToolArgs>(next).also { if (firstContinuation == null) firstContinuation = it }
        }
        result.containsKey("nextOffset") -> args.copy(offset = result.getValue("nextOffset").jsonPrimitive.int).also {
          assertTrue(it.offset in 0..280)
        }
        else -> break
      }
    }
    assertEquals(2, limitCount)
    assertEquals(graph.size, received.size)
    assertEquals(graph.keys, received.toSet())
    assertEquals(graph.size, reads)
    val continuation = checkNotNull(firstContinuation)
    assertEquals(firstContinuedPage, chainCoordinates(session.execute("read_reply_chain", continuation)))
    assertEquals(graph.size, reads)
    for (invalid in listOf(continuation.copy(tid = 99), continuation.copy(cursor = "unknown"))) {
      assertEquals("invalid_parameters", session.execute("read_reply_chain", invalid).getValue("status").jsonPrimitive.content)
    }
    assertEquals(graph.size, reads)
    assertEquals("invalid_parameters", session.execute("read_reply_chain", ForumToolArgs(tid = 10, pid = 1, offset = 279)).getValue("status").jsonPrimitive.content)

  }

  @Test fun loadingNewDownstreamBetweenPagesDoesNotReorderOrSkipBodies() = runTest {
    for (tool in listOf("read_floor", "read_topic_page")) {
      val graph = (1L..25L).associateWith { pid -> chainFloor(pid, if (pid < 25) listOf(10L to pid + 1) else emptyList()) } +
        mapOf(100L to chainFloor(100, listOf(10L to 1L, 10L to 101L)), 101L to chainFloor(101))
      val reads = mutableListOf<Long>()
      var blocked = false
      val session = ForumToolSession(initial, { params ->
        val pid = params.pid ?: 100L
        reads += pid
        page().copy(floors = listOf(graph.getValue(pid)), page = params.page)
      }, { if (blocked) listOf(FilterRule("b", FilterRuleKind.KEYWORD, FilterRuleOrigin.LOCAL, "发言 21", false)) else emptyList() }, limiter = ForumReadLimiter(0))
      val root = ForumToolArgs(tid = 10, pid = 1)
      val first = session.execute("read_reply_chain", root)
      assertEquals((1L..20L).map { 10L to it }, chainCoordinates(first))
      val next = Json.decodeFromJsonElement<ForumToolArgs>(first.getValue("continuation"))
      session.execute(tool, if (tool == "read_floor") ForumToolArgs(tid = 10, pid = 100) else ForumToolArgs(tid = 10, page = 2))
      val second = session.execute("read_reply_chain", next)
      assertEquals((21L..25L).map { 10L to it }, chainCoordinates(second))
      assertEquals(first["rangeVersion"], second["rangeVersion"])
      assertFalse(second.containsKey("continuation"))
      assertFalse(101L in reads)
      assertTrue(second.getValue("scope").jsonPrimitive.content.contains("创建新范围"))
      val newFirst = session.execute("read_reply_chain", root)
      val newNext = Json.decodeFromJsonElement<ForumToolArgs>(newFirst.getValue("continuation"))
      val newSecond = session.execute("read_reply_chain", newNext)
      val all = chainCoordinates(newFirst) + chainCoordinates(newSecond)
      assertEquals(graph.keys.map { 10L to it }.toSet(), all.toSet())
      assertEquals(graph.size, all.size)
      assertNotEquals(first["rangeVersion"], newFirst["rangeVersion"])
      assertEquals(second, session.execute("read_reply_chain", next))
      assertEquals(graph.size, reads.size)
      blocked = true
      val filtered = session.execute("read_reply_chain", next)
      assertEquals(1, filtered.getValue("blocked").jsonPrimitive.int)
      assertEquals((22L..25L).map { 10L to it }, chainCoordinates(filtered))
      assertEquals(second["cursor"], filtered["cursor"])
      assertFalse(filtered.containsKey("continuation"))
    }
  }

}
