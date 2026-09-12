package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.EnvelopeShape
import com.chasel.ng2n.core.net.NgaEnvelope
import com.chasel.ng2n.core.net.NgaServerError
import com.chasel.ng2n.core.net.UNKNOWN_SERVER_CODE
import com.chasel.ng2n.core.net.parseNgaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidateTest {

  private fun envelope(text: String, shape: EnvelopeShape = EnvelopeShape.WRAPPED): NgaEnvelope =
    parseNgaJson(text, "test", shape)

  private val foreign = """{"data":{"code":0,"msg":"","result":[]},"time":1}"""

  @Test
  fun `read_php——有楼层表就放行,陌生 JSON 否决`() {
    assertNull(rejectNonTopicDetail(envelope("""{"data":{"__R":{},"__ROWS":0},"time":1}""")))
    assertNull(rejectNonTopicDetail(envelope("""{"data":{"__T":{"tid":1}},"time":1}""")))
    assertNotNull(rejectNonTopicDetail(envelope(foreign)))
  }

  @Test
  fun `app_api——data 或 other 在场即可,读的是顶层`() {
    val real = """{"data":{"0":{"_id":"wow","name":"魔兽世界"}},"other":{},"time":1}"""
    assertNull(rejectNonBoardTree(envelope(real, EnvelopeShape.BARE)))
    assertNotNull(
      rejectNonBoardTree(envelope("""{"code":0,"msg":""}""", EnvelopeShape.BARE)),
    )
  }

  @Test
  fun `noti 与 ucp——数据都在 data 的 0 上`() {
    assertNull(rejectNonNotificationFeed(envelope("""{"data":{"0":""},"time":1}""")))
    assertNotNull(rejectNonNotificationFeed(envelope(foreign)))

    assertNull(rejectNonUcpPayload(envelope("""{"data":{"0":{"uid":1}},"time":1}""")))
    assertNotNull(rejectNonUcpPayload(envelope(foreign)))
    assertNull(rejectNonUcpPayload(envelope("""{"data":{},"time":1}""")))
  }

  @Test
  fun `forum_php——数字键的结果或 __MESSAGE 提示位`() {
    assertNull(rejectNonBoardSearch(envelope("""{"data":{"0":{"fid":422,"name":"炉石传说"}},"time":1}""")))
    assertNull(rejectNonBoardSearch(envelope("""{"data":{"__MESSAGE":{"0":2048}},"time":1}""")))
    assertNotNull(rejectNonBoardSearch(envelope(foreign)))
  }

  @Test
  fun `假错误一律放行——那是正常终止,不是坏组合`() {
    val fake = NgaEnvelope(
      root = kotlinx.serialization.json.JsonObject(emptyMap()),
      data = null,
      fakeError = NgaServerError(UNKNOWN_SERVER_CODE, "找不到用户"),
    )
    for (judge in listOf(
      ::rejectNonTopicDetail,
      ::rejectNonBoardTree,
      ::rejectNonNotificationFeed,
      ::rejectNonUcpPayload,
      ::rejectNonBoardSearch,
      ::rejectNonTopicList,
    )) {
      assertNull(judge(fake), "假错误不该被否决")
    }
  }

  @Test
  fun `没有 data 一律否决,理由与 rejectNonTopicList 同一句`() {
    val onlyError = NgaEnvelope(root = kotlinx.serialization.json.JsonObject(emptyMap()), data = null)
    assertEquals("响应里没有 data", rejectNonTopicDetail(onlyError))
    assertEquals("响应里没有 data", rejectNonUcpPayload(onlyError))
    assertEquals("响应里没有 data", rejectNonTopicList(onlyError))
  }
}
