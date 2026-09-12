package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * `envelope` domain 全量对拍(18 条,票 04)。
 *
 * `root` 故意不进 `expected`:它等于 `JSON.parse(sanitize(text))`,已经被 `sanitize`
 * domain 锁住了。`expected.data` 为 `null` 表示 TS 侧的 `undefined`(只有 error 的响应)。
 * 抛错那几条的 `retryable` 必须对上——它决定反封锁链走不走下去(ADR-0002)。
 */
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

/** `input.shape` 缺省是 `wrapped`(顶层既没 data 也没 error 一律抛 parse)。 */
private fun GoldenCase.shape(): EnvelopeShape = when (val raw = stringFieldOrNull("shape")) {
  null, "wrapped" -> EnvelopeShape.WRAPPED
  "bare" -> EnvelopeShape.BARE
  else -> error("$resourcePath: 认不出的 shape `$raw`")
}
