package com.chasel.ng2n.data.ai

import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.core.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PersonaHistoryReaderTest {
  @Test fun readsBothStreamsAndMergesWithoutFetchingTopicBodies() = runTest {
    val calls = mutableListOf<Pair<UserPostKind, Int>>()
    val reader = PersonaHistoryReader({ _, kind, page ->
      calls += kind to page
      TopicList(topics = if (page > 2) emptyList() else (1L..100).map { i ->
        val n = (3 - page) * 100 + i
        Topic(tid = n, subject = "标题", author = "本人", postedAt = n * 2,
          reply = if (kind == UserPostKind.REPLIES) TopicReply(n, "回复", n * 2 + 1) else null)
      })
    }, ForumReadLimiter(0))
    val context = reader.load(7, "本人", emptyList())
    assertEquals(300, context.sources.size)
    assertEquals(150, context.sources.count { it.part == "summary" })
    assertEquals(6, calls.size)
    assertTrue(context.sources.all { it.images.isEmpty() })
  }

  @Test fun replyBelongsToQueriedUserEvenWhenTopicAuthorIsAnonymous() = runTest {
    val reader = PersonaHistoryReader({ _, kind, page -> TopicList(topics = if (page > 1 || kind == UserPostKind.TOPICS) emptyList() else
      listOf(Topic(tid = 1, subject = "主题", author = "匿名假名", authorId = -1, anonymous = true, reply = TopicReply(3, "本人回复", 10)))) }, ForumReadLimiter(0))
    val context = reader.load(7, "本人", emptyList())
    assertEquals(1, context.sources.size)
    assertEquals("本人", context.sources.single().author)
    assertEquals(0, context.blocked)
    assertFalse(context.material().contains("匿名假名"))
  }

  @Test fun partialFailureKeepsSuccessfulStreamAndRepeatedPagesStop() = runTest {
    var calls = 0
    val reader = PersonaHistoryReader({ _, kind, _ ->
      calls++
      if (kind == UserPostKind.TOPICS) error("offline")
      TopicList(topics = listOf(Topic(tid = 1, subject = "主题", author = "本人", reply = TopicReply(3, "发言", 10))))
    }, ForumReadLimiter(0))
    val context = reader.load(7, "本人", emptyList())
    assertEquals(1, context.sources.size)
    assertTrue(context.note!!.contains("历史读取不完整"))
    assertEquals(3, calls)
  }

  @Test fun personalSessionRejectsImagesAndExposesBoundedHistoryAfterRestore() = runTest {
    val context = buildPersonaContext((1L..40).map { Topic(tid = it, subject = "主题", author = "本人",
      reply = TopicReply(it * 10, "文".repeat(1800), it)) } + Topic(tid = 99, subject = "主题", author = "本人",
      reply = TopicReply(990, "长".repeat(PERSONA_SAMPLE_TEXT_LIMIT + 800), 99)), "本人")
    assertTrue(context.sources.first { it.pid == 990L }.text.startsWith("长".repeat(PERSONA_SAMPLE_TEXT_LIMIT)))
    assertTrue(context.sources.first { it.pid == 990L }.text.endsWith("重新读取）"))
    val session = ForumToolSession(context, { error("unexpected forum read") }, { emptyList() })
    assertFalse(session.allowImages)
    for (name in listOf("read_image", "list_images")) {
      val rejected = session.execute(name, ForumToolArgs())
      assertEquals("disabled", rejected["status"]!!.jsonPrimitive.content)
      assertTrue(rejected["detail"]!!.jsonPrimitive.content.contains("个人分析仅支持文本"))
    }
    val first = session.execute("read_user_history", ForumToolArgs())
    assertEquals(context.material().length, first["material"]!!.jsonPrimitive.content.length)
    assertFalse(first.containsKey("nextOffset"))
    assertEquals(context.sampleCount, first["sampleCount"]!!.jsonPrimitive.int)
    val rest = session.execute("read_user_history", ForumToolArgs(offset = PERSONA_INLINE_LIMIT))
    assertEquals(context.material().length - PERSONA_INLINE_LIMIT, rest["material"]!!.jsonPrimitive.content.length)
    assertFalse(rest.containsKey("nextOffset"))
  }

  @Test fun oversizedPersonalHistoryStillPagesWithContinuation() = runTest {
    val huge = TopicContext((1..3).map { AiSource("s$it", it.toLong(), it * 10L, -1, 1, "本人", "2026-09-0$it",
      "文".repeat(PERSONA_HISTORY_PAGE / 2), emptyList(), "floor") }, null, 0, emptyList(), title = "本人",
      entryKind = "个人", sampleCount = 3)
    val session = ForumToolSession(huge, { error("unexpected forum read") }, { emptyList() })
    val first = session.execute("read_user_history", ForumToolArgs())
    assertEquals(PERSONA_HISTORY_PAGE, first["material"]!!.jsonPrimitive.content.length)
    assertEquals(PERSONA_HISTORY_PAGE, first["nextOffset"]!!.jsonPrimitive.int)
    val rest = session.execute("read_user_history", ForumToolArgs(offset = PERSONA_HISTORY_PAGE))
    assertFalse(rest.containsKey("nextOffset"))
    assertEquals(first["totalCharacters"]!!.jsonPrimitive.int,
      PERSONA_HISTORY_PAGE + rest["material"]!!.jsonPrimitive.content.length)
  }
}
