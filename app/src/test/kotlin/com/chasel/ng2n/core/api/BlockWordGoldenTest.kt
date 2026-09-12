package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test

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
