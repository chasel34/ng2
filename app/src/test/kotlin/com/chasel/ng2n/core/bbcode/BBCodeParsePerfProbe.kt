package com.chasel.ng2n.core.bbcode

import com.chasel.ng2n.golden.Goldens
import kotlin.test.Test
import kotlin.test.assertTrue

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
