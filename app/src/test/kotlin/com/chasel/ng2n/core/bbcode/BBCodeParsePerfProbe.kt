package com.chasel.ng2n.core.bbcode

import com.chasel.ng2n.golden.Goldens
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 长正文解析耗时抽查(票 09 验收项 3)。给票 13「后台一次性预转换」提供预算参考。
 *
 * **这是 JVM 桌面数据,不是性能裁决**:真机(小米 17)与 ART 的数字不同,性能只在票 19
 * 裁决(spec §五)。这里只要一个数量级:一屏楼层几十条正文,一次性转换该不该分帧。
 *
 * 断言口径故意放得极松(单条 < 200ms),只为防「某次改动把解析退化成指数级」——
 * 不做性能门禁,免得在忙的构建机上假红。真实数字看 stdout 那几行。
 */
class BBCodeParsePerfProbe {

  @Test
  fun `最长语料的解析耗时抽查`() {
    val cases = Goldens.load("bbcode")
      .sortedByDescending { it.inputString().length }
      .take(3) + Goldens.load("bbcode").filter { it.name == "coverage-all-joined" }

    val report = StringBuilder("\n=== BBCode 解析耗时抽查(JVM 桌面,仅供票 13 预算参考)===\n")
    for (case in cases.distinctBy { it.name }) {
      val source = case.inputString()
      repeat(WARMUP) { parseBBCode(source) }

      val start = System.nanoTime()
      repeat(ROUNDS) { parseBBCode(source) }
      val perParse = (System.nanoTime() - start).toDouble() / ROUNDS / 1_000_000.0

      // 序列化(进 Room 帖子缓存那一步)也一起量,票 13 要的是「解析 + 落盘」的总账
      val encodeStart = System.nanoTime()
      repeat(ROUNDS) { encodeBBCodeToString(parseBBCode(source)) }
      val perParseAndEncode = (System.nanoTime() - encodeStart).toDouble() / ROUNDS / 1_000_000.0

      report.append(
        "%-24s 输入 %6d 码元  解析 %8.4f ms  解析+序列化 %8.4f ms\n"
          .format(case.name, source.length, perParse, perParseAndEncode),
      )
      assertTrue(perParse < 200.0, "${case.name} 解析退化到 $perParse ms/次,远超预期")
    }
    println(report)
  }

  private companion object {
    const val WARMUP = 200
    const val ROUNDS = 100
  }
}
