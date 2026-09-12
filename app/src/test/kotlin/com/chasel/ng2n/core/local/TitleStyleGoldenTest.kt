package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.longFieldOrNull
import com.chasel.ng2n.golden.runGoldenDomain
import com.chasel.ng2n.golden.unknownField
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `title-style` domain 全量对拍(35 条)。
 *
 * 锁住的三处:`topic_misc`(无 padding base64 TLV)**优先于** `titlefont`;
 * 颜色位互斥按 红>蓝>绿>橙>银 取第一个、字形位叠加;
 * 有符号修正**只对 sfid**,stid(主题 id)与掩码(位字段)都按无符号读。
 */
class TitleStyleGoldenTest {

  @Test
  fun `title-style 金样本全量对拍`() = runGoldenDomain("title-style") {
    fn("parseTopicMisc") { case -> parseTopicMisc(case.unknownField("raw")).toGoldenMap() }
    fn("signedBoardId") { case -> signedBoardId(case.longFieldOrNull("value")) }
    fn("decodeTitleStyle") { case ->
      decodeTitleStyle(
        titlefont = case.unknownField("titlefont"),
        topicMisc = case.unknownField("topicMisc"),
      ).toGoldenMap()
    }
  }

  // --- 手工移植:`title-style.test.ts` 里的边界说明 -----------------------------

  @Test
  fun `2 的 32 次方及以上不动——那不是 u32,硬减会把大 id 改坏`() {
    assertEquals(4_294_967_296L, signedBoardId(4_294_967_296L))
    assertEquals(2_147_483_647L, signedBoardId(2_147_483_647L))
    assertEquals(-7L, signedBoardId(4_294_967_289L))
  }

  @Test
  fun `未知 TLV type 照样按 5 字节跳过`() {
    // type=9(未知)一条 + type=1 掩码一条:前一条不该把后一条挤掉
    assertEquals(TopicMisc(mask = 32), parseTopicMisc("CQAAA0MBAAAAIA"))
  }

  @Test
  fun `titlefont 的数字字符串与数字等价`() {
    assertEquals(decodeTitleStyle(titlefont = 196.0), decodeTitleStyle(titlefont = "196"))
    assertEquals(PLAIN_TITLE_STYLE, decodeTitleStyle(titlefont = "abc"))
    assertEquals(PLAIN_TITLE_STYLE, decodeTitleStyle(titlefont = "  "))
  }
}

private fun TopicMisc.toGoldenMap(): Map<String, Any?> = buildMap {
  // README 规范 2:值为 undefined 的键在导出时整个删掉,所以缺席的字段不能写成 null
  mask?.let { put("mask", it) }
  sfid?.let { put("sfid", it) }
  stid?.let { put("stid", it) }
}

private fun TitleStyle.toGoldenMap(): Map<String, Any?> = buildMap {
  put("bold", bold)
  color?.let { put("color", it.name.lowercase()) }
  put("italic", italic)
  put("underline", underline)
}
