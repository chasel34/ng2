package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class EnvelopeGoldenTest {

  @Test
  fun `envelope 金样本全量对拍`() = runGoldenDomain("envelope") {
    throwsDescribedBy(NgaErrorThrowDescriber)
    fn("parseNgaJson") { case ->
      val envelope = parseNgaJson(case.stringField("text"), case.stringFieldOrNull("via"), case.shape())
      buildJsonObject {
        put("data", envelope.data ?: JsonNull)
        envelope.time?.let { put("time", it) }
        envelope.fakeError?.let { fake ->
          put(
            "fakeError",
            buildJsonObject {
              put("code", fake.code)
              put("message", fake.message)
            },
          )
        }
      }
    }
  }
}

private fun GoldenCase.shape(): EnvelopeShape = when (val raw = stringFieldOrNull("shape")) {
  null, "wrapped" -> EnvelopeShape.WRAPPED
  "bare" -> EnvelopeShape.BARE
  else -> error("$resourcePath: 认不出的 shape `$raw`")
}
