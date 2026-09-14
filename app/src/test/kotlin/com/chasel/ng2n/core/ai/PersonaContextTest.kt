package com.chasel.ng2n.core.ai

import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.*
import org.junit.Assert.*
import org.junit.Test

class PersonaContextTest {
  private fun topic(tid: Long, time: Long, pid: Long? = null) = Topic(tid = tid, subject = "标题", author = "本人", authorId = 7,
    postedAt = time, reply = pid?.let { TopicReply(it, "[quote]他人[/quote]本人[img]https://example.com/a.jpg[/img]", time) })

  @Test fun combinesTimeOrderDeduplicatesIndependentPostsAndLimitsCombinedCount() {
    val merged = mergePersonaPosts(listOf(topic(1, 1), topic(2, 10, 20), topic(2, 9, 20), topic(1, 1, 0), topic(2, 8, 21)))
    assertEquals(listOf("p20", "p21", "t1"), merged.map(::personaPostKey))
    val many = (1L..400).map { topic(it, it, if (it % 2 == 0L) it else null) }
    val result = buildPersonaContext(many, "本人")
    assertEquals(300, result.sources.size)
    assertEquals(150, result.sources.count { it.part == "summary" })
    assertEquals(400L, java.time.Instant.parse(result.sources.first().postedAt).epochSecond)
    assertEquals(101L, java.time.Instant.parse(result.sources.last().postedAt).epochSecond)
    assertEquals(3, buildPersonaContext(listOf(topic(1, 1), topic(2, 2), topic(3, 3)), "本人").sources.size)
    assertTrue(buildPersonaContext(emptyList(), "本人").ranges.first { it.label == "合计" }.details.toString().contains("无可访问样本"))
  }

  @Test fun spanFilteringTitlesOnlyAndQuotedTextRemainExplicit() {
    val context = buildPersonaContext(listOf(topic(1, 1704067200), topic(2, 1706745600, 20), topic(3, 1705000000).copy(denied = true)), "本人")
    assertEquals(0, context.blocked)
    assertEquals("1 条", context.ranges.first { it.label == "匿名或无权限" }.amount)
    assertEquals("2024-01-01 至 2024-02-01", context.ranges.first { it.label == "合计" }.details.first { it.first == "时间跨度" }.second)
    assertEquals("标题：标题", context.sources.last().text)
    assertTrue(context.sources.first().text.contains("引用他人，非本人立场"))
    assertFalse(context.material().contains("https://example.com/a.jpg"))
    assertNull(context.imageInput)
    assertTrue(context.sources.all { it.images.isEmpty() })
    val filtered = buildPersonaContext(listOf(topic(2, 2, 20)), "本人", listOf(FilterRule("a", FilterRuleKind.KEYWORD, FilterRuleOrigin.OFFICIAL, "本人", false)))
    assertTrue(filtered.sources.isEmpty())
    assertEquals(1, filtered.blocked)
    assertFalse(canAnalyzePersona(-1))
    assertFalse(canAnalyzePersona(0))
    assertTrue(canAnalyzePersona(7))
    val anonymous = buildPersonaContext(listOf(topic(4, 4, 40).copy(anonymous = true), topic(5, 5).copy(denied = true), topic(6, 6, 60)), "本人",
      listOf(FilterRule("b", FilterRuleKind.KEYWORD, FilterRuleOrigin.OFFICIAL, "本人", false)))
    assertEquals(0, anonymous.sampleCount)
    assertEquals(1, anonymous.blocked)
    assertEquals("2 条", anonymous.ranges.first { it.label == "匿名或无权限" }.amount)
    assertEquals("1 条", anonymous.ranges.first { it.label == "屏蔽规则" }.amount)
    assertTrue(anonymous.note!!.contains("匿名或无权限发言 2 条不在样本内"))
    val long = buildPersonaContext(listOf(topic(7, 7, 70).copy(reply = TopicReply(70, "字".repeat(PERSONA_SAMPLE_TEXT_LIMIT + 500), 7))), "本人")
    assertEquals(PERSONA_SAMPLE_TEXT_LIMIT + "…（本条只保留前 $PERSONA_SAMPLE_TEXT_LIMIT 字，需要原句用 read_floor 重新读取）".length,
      long.sources.single().text.length)
    val bulky = buildPersonaContext((1L..20).map { topic(it, it, it * 10).copy(reply = TopicReply(it * 10, "字".repeat(1800), it)) }, "本人")
    assertTrue(bulky.note!!.contains("首轮只内联前 $PERSONA_INLINE_LIMIT 字"))
    assertEquals(PERSONA_INLINE_LIMIT, personaInlineMaterial(bulky.material()).substringBefore("\n（本轮只内联").length)
    assertTrue(personaInlineMaterial(bulky.material()).contains("用 read_user_history 从 offset=$PERSONA_INLINE_LIMIT 续读"))
    assertEquals("短资料", personaInlineMaterial("短资料"))
    val many = buildPersonaContext((1L..PERSONA_SAMPLE_LIMIT).map { topic(it, it, it * 10).copy(reply = TopicReply(it * 10, "字".repeat(40), it)) }, "本人")
    assertTrue(many.sources.sumOf { it.text.length } < PERSONA_INLINE_LIMIT)
    assertTrue(many.material().length > PERSONA_INLINE_LIMIT)
    assertTrue(many.note!!.contains("首轮只内联前 $PERSONA_INLINE_LIMIT 字"))
    assertTrue(many.ranges.first { it.label == "合计" }.details.first { it.first == "首轮内联" }.second.startsWith("前 $PERSONA_INLINE_LIMIT 字 / 共约 "))
    val oversized = buildPersonaContext((1L..PERSONA_SAMPLE_LIMIT).map { topic(it, it, it * 10).copy(reply = TopicReply(it * 10, "字".repeat(PERSONA_SAMPLE_TEXT_LIMIT), it)) }, "本人")
    assertTrue(oversized.material().length <= PERSONA_MATERIAL_LIMIT)
    assertTrue(oversized.sampleCount in 1 until PERSONA_SAMPLE_LIMIT)
    assertEquals("${PERSONA_SAMPLE_LIMIT - oversized.sampleCount} 条", oversized.ranges.first { it.label == "长度上限" }.amount)
    assertTrue(oversized.note!!.contains("较早的 ${PERSONA_SAMPLE_LIMIT - oversized.sampleCount} 条发言超出单次可读长度"))
    assertEquals(oversized.sampleCount, oversized.sources.size)
    assertEquals("s1", oversized.sources.first().id)
    assertTrue(PERSONA_HISTORY_PAGE >= PERSONA_MATERIAL_LIMIT)
    val small = buildPersonaContext(listOf(topic(1, 1, 10)), "本人")
    assertEquals("全部内联", small.ranges.first { it.label == "合计" }.details.first { it.first == "首轮内联" }.second)
    assertFalse(small.note!!.contains("首轮只内联"))
    assertEquals(small.material(), personaInlineMaterial(small.material()))
    assertTrue(small.ranges.none { it.label == "长度上限" })
    assertEquals(listOf("查看倾向依据", "查找相反表述", "查看观点变化"), BuiltinQuickActions.forEntry("个人").map { it.label })
  }

  @Test fun reportRejectsInventedSourcesAndUnprocessedEvidenceAndTruncatedJson() {
    val sources = buildPersonaContext(listOf(topic(1, 1)), "本人").sources
    val valid = """{"overview":"仅一条标题","processedSourceIds":["s1"],"interests":[{"title":"偏好","body":"偶尔提及","evidence":["s1"]}],"positions":[],"judgment":"证据不足","timeline":[],"boundary":"不推断身份"}"""
    assertNotNull(parsePersonaReport(valid, sources))
    assertNull(parsePersonaReport(valid.replace("s1", "s99"), sources))
    assertNull(parsePersonaReport(valid.replace("\"processedSourceIds\":[\"s1\"]", "\"processedSourceIds\":[]"), sources))
    assertNull(parsePersonaReport(valid.dropLast(1), sources))
    val twoSamples = buildPersonaContext(listOf(topic(1, 1), topic(2, 2)), "本人").sources
    val unprocessed = """{"overview":"只处理一条","processedSourceIds":["s1"],"interests":[{"title":"偏好","body":"依据未处理","evidence":["s2"]}],"positions":[],"judgment":"证据不足","timeline":[],"boundary":"边界"}"""
    assertNull(parsePersonaReport(unprocessed, twoSamples, 2))
    val overlapping = """{"overview":"重合","processedSourceIds":["s1","s2"],"interests":[{"title":"偏好","body":"重合","evidence":["s1"],"counter":["s1"]}],"positions":[],"judgment":"证据不足","timeline":[],"boundary":"边界"}"""
    assertNull(parsePersonaReport(overlapping, twoSamples, 2))
    val strayTimeline = """{"overview":"时间线越界","processedSourceIds":["s1"],"interests":[],"positions":[],"judgment":"证据不足","timeline":[{"period":"2024","body":"变化","sources":["s2"]}],"boundary":"边界"}"""
    assertNull(parsePersonaReport(strayTimeline, twoSamples, 2))
    val partial = """{"overview":"只处理一条","processedSourceIds":["s1"],"interests":[{"title":"偏好","body":"仅一条","evidence":["s1"]}],"positions":[],"judgment":"证据不足","timeline":[],"boundary":"边界"}"""
    assertEquals(listOf("s1"), parsePersonaReport(partial, twoSamples, 2)!!.processedSourceIds)
    val counted = """{"overview":"两条都处理","processedSourceIds":["s1","s2"],"interests":[{"title":"偏好","body":"命中","mentions":2,"bodyTail":"，条件有限","evidence":["s1"]}],"positions":[],"judgment":"有限","timeline":[],"boundary":"边界"}"""
    assertEquals(2, parsePersonaReport(counted, twoSamples, 2)!!.interests.single().hits)
    val overCounted = parsePersonaReport(counted.replace("\"mentions\":2", "\"mentions\":9"), twoSamples, 2)!!.interests.single()
    assertEquals(0, overCounted.mentions)
    assertEquals(1, overCounted.hits)
    assertEquals("，条件有限", overCounted.bodyTail)
    assertEquals(0, parsePersonaReport(counted.replace("\"mentions\":2", "\"mentions\":-3"), twoSamples, 2)!!.interests.single().mentions)
  }

  @Test fun reportAcceptsEvidenceFromToolReadSourcesAndCountsOnlyInitialSamples() {
    val samples = buildPersonaContext(listOf(topic(1, 1), topic(2, 2)), "本人").sources
    // 技能要求对只有标题的样本用 read_floor 补读主楼，补读结果在样本之后另行编号。
    val readFloor = AiSource("s3", 1, 0, 0, 1, "本人", "2024-01-01T00:00:00Z", "补读到的主楼正文", emptyList(), "floor")
    val registered = samples + readFloor
    val report = """{"overview":"两条样本，补读一条主楼","processedSourceIds":["s1","s2"],"interests":[{"title":"偏好","body":"依据来自补读主楼","evidence":["s3"],"counter":["s1"]}],"positions":[],"judgment":"有限","timeline":[{"period":"2024","body":"变化","sources":["s3"]}],"boundary":"边界"}"""
    val parsed = parsePersonaReport(report, registered, 2)
    assertNotNull(parsed)
    assertEquals(2, parsed!!.processedSampleCount(2))
    assertEquals(listOf("s3"), parsed.interests.single().evidence)
    // 补读来源仍必须真实存在，且属于初始样本的编号仍要先列入已处理。
    assertNull(parsePersonaReport(report.replace("\"s3\"", "\"s9\""), registered, 2))
    val citesUnprocessedSample = """{"overview":"只确认一条","processedSourceIds":["s1"],"interests":[{"title":"偏好","body":"引用未确认样本","evidence":["s2"]}],"positions":[],"judgment":"有限","timeline":[],"boundary":"边界"}"""
    assertNull(parsePersonaReport(citesUnprocessedSample, registered, 2))
  }

  @Test fun countPillCarriesOnlyTheNumberAndJoinsBodyIntoOneSentence() {
    val samples = buildPersonaContext(listOf(topic(1, 1), topic(2, 2), topic(3, 3)), "本人").sources
    val report = """{"overview":"三条样本","processedSourceIds":["s1","s2","s3"],"interests":[{"title":"关注新区预约","body":"以主题形式关注新区预约的发言有","mentions":3,"bodyTail":"，集中在 2026 年 8 月。","evidence":["s1","s2"],"counter":[]}],"positions":[],"judgment":"有限","timeline":[],"boundary":"边界"}"""
    val card = parsePersonaReport(report, samples, 3)!!.interests.single()
    assertEquals("3 条", card.countPill())
    assertEquals("以主题形式关注新区预约的发言有3 条，集中在 2026 年 8 月。", card.sentence())
    val noTail = card.copy(bodyTail = "")
    assertEquals("以主题形式关注新区预约的发言有1 条", noTail.copy(mentions = 0, evidence = listOf("s1")).sentence())
    assertEquals("以主题形式关注新区预约的发言有3 条", noTail.sentence())
  }

  @Test fun pendingNoteOnlyClaimsADraftWhenTheRoundActuallyShowedText() {
    assertEquals("已读取 19 条；本轮尚无已确认处理完成的样本，19 条待确认处理。已有草稿保留，可继续。",
      personaPendingNote(19, draftShown = true, sourceCount = 4))
    // 达到限制且本轮零文字：同屏的状态卡说明文字去向，这里不能再宣称有草稿。
    assertEquals("已读取 19 条；本轮尚无已确认处理完成的样本，19 条待确认处理。已登记 4 个来源。",
      personaPendingNote(19, draftShown = false, sourceCount = 4))
    assertEquals("已读取 19 条；本轮尚无已确认处理完成的样本，19 条待确认处理。",
      personaPendingNote(19, draftShown = false, sourceCount = 0))
  }

  @Test fun evidenceKindFollowsTheRealFloorForSamplesAndToolReadSources() {
    val samples = buildPersonaContext(listOf(topic(1, 1), topic(2, 2, 20)), "本人").sources
    assertEquals("回复", personaEvidenceKind(samples.first { it.part == "floor" }))
    assertEquals("主题标题", personaEvidenceKind(samples.first { it.part == "summary" }))
    // read_floor(tid, pid=0) 补读回来的主楼与初始标题样本同源，不能标成回复。
    val readFloor = AiSource("s3", 1, 0, 0, 1, "本人", "2024-01-01T00:00:00Z", "补读到的主楼正文", emptyList(), "floor")
    assertEquals("主楼", personaEvidenceKind(readFloor))
    assertEquals("回复", personaEvidenceKind(readFloor.copy(pid = 45, floor = 12)))
  }
}
