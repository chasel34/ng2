package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test

/**
 * `api/block-word` domain 全量对拍(14 条,票 07)。
 *
 * 读回来的是**一段多行纯文本**而不是结构化数据(API 文档 §11.5):
 * 第 2 行空格分隔的关键词、第 3 行空格分隔的 `uid/用户名`;行数不够要当空表。
 * `serialize` 那两条锁的是「解出来再拼回去与原文一致」——网页版写的表,
 * 我们改一条也不该破坏其余部分。
 */
class BlockWordGoldenTest {

  @Test
  fun `api-block-word 金样本全量对拍`() = runGoldenDomain("api/block-word") {
    fn("parseBlockWords") { case -> golden(parseBlockWords(case.valueField())) }
    fn("serializeBlockWords") { case ->
      val list: BlockWordList = ApiGoldenJson.decodeFromJsonElement(case.input)
      serializeBlockWords(list)
    }
    fn("blockWordError") { case ->
      val label = case.stringFieldOrNull("label")
      if (label == null) {
        blockWordError(case.stringField("text"))
      } else {
        blockWordError(case.stringField("text"), label)
      }
    }
  }
}
