package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComboPoisonTest {

  private val rejectNonTopicList: (NgaEnvelope) -> String? = { envelope ->
    val data = envelope.data as? JsonObject
    if (data != null && listOf("__T", "__F", "__ROWS").any { data.containsKey(it) }) {
      null
    } else {
      "响应里没有主题列表结构"
    }
  }

  private val good = """{"data":{"__T":{"0":{"tid":1,"subject":"标题","author":"a"}},""" +
    """"__F":{"fid":650,"name":"原神"},"__ROWS":100,"__T__ROWS_PAGE":35},"time":1}"""

  private val emptyBoard =
    """{"data":{"__T":{},"__F":{"fid":650,"name":"原神"},"__ROWS":0,"__T__ROWS_PAGE":35},"time":1}"""

  private val noTopicList = """{"data":{"__CU":{"uid":10000001}},"time":1}"""

  private fun boardRequest(fid: Int) = readRequest(
    "thread.php",
    queryOf("fid" to fid, "page" to 1),
    validate = rejectNonTopicList,
  )

  private fun clientWith(
    cache: ComboCache = InMemoryComboCache(),
    respond: (String) -> String,
  ): Pair<NgaClient, RecordingTransport> {
    val transport = RecordingTransport { request ->
      val body = respond(comboOf(request))
      if (body == BLOCKED_HTML) blocked() else ok(body)
    }
    val client = testClient(
      transport,
      strategies = listOf(FormatRotationStrategy()),
      comboCache = cache,
      settings = FakeSettings(hostValue = "https://bbs.nga.cn"),
    )
    return client to transport
  }

  private fun topicCount(result: NgaResult): Int =
    ((result.data as JsonObject)["__T"] as? JsonObject)?.size ?: 0

  @Test
  fun `一次瞬时失败之后,坏组合不进缓存,后面的版块照常出主题`() = runTest {
    var blockedOnce = false
    val cache = InMemoryComboCache()
    val (client, _) = clientWith(cache) { combo ->
      when {
        combo == "__output=8@https://bbs.nga.cn" && !blockedOnce -> {
          blockedOnce = true
          BLOCKED_HTML
        }
        combo.startsWith("__output=8") -> good
        else -> noTopicList
      }
    }

    assertEquals(1, topicCount(client.execute(boardRequest(414))))

    assertEquals(ResponseFormat.JSON, cache.get("thread.php")?.format)

    for (fid in listOf(428, 481, 650)) {
      assertEquals(1, topicCount(client.execute(boardRequest(fid))))
    }
  }

  @Test
  fun `所有组合都拿不到主题列表时是报错,不是「这个版块还没有主题」`() = runTest {
    val cache = InMemoryComboCache()
    val (client, transport) = clientWith(cache) { noTopicList }

    val error = assertThrowsNga { client.execute(boardRequest(650)) }
    assertTrue(error.text.contains("没有主题列表结构"), error.text)
    assertNull(cache.get("thread.php"))
    assertTrue(transport.combos().toSet().size > 1)
  }

  @Test
  fun `真的空版块仍然是「成功的 0 条」,不报错`() = runTest {
    val cache = InMemoryComboCache()
    val (client, _) = clientWith(cache) { emptyBoard }

    val result = client.execute(boardRequest(650))

    assertEquals(0, topicCount(result))
    assertTrue((result.data as JsonObject).containsKey("__T"))
    assertEquals(ResponseFormat.JSON, cache.get("thread.php")?.format)
  }

  @Test
  fun `缓存过期后会重新试探默认组合(自愈,不必杀进程)`() = runTest {
    var clock = 1_000_000L
    val cache = InMemoryComboCache(ttlMs = 60_000) { clock }
    var blockDefault = false
    val (client, transport) = clientWith(cache) { combo ->
      if (blockDefault && combo.endsWith("@https://bbs.nga.cn")) BLOCKED_HTML else good
    }

    client.execute(boardRequest(650))
    assertEquals("https://bbs.nga.cn", cache.get("thread.php")?.host)

    blockDefault = true
    client.execute(boardRequest(321))
    assertEquals("https://ngabbs.com", cache.get("thread.php")?.host)

    blockDefault = false
    transport.clear()
    client.execute(boardRequest(436))
    assertEquals(listOf("__output=8@https://ngabbs.com"), transport.combos())

    clock += 60_001
    transport.clear()
    client.execute(boardRequest(414))
    assertEquals("__output=8@https://bbs.nga.cn", transport.combos().first())
    assertEquals("https://bbs.nga.cn", cache.get("thread.php")?.host)
  }

  @Test
  fun `顶层既没有 data 也没有 error 时报解析错,不是一份空数据`() {
    val error = assertThrowsNga { parseNgaJson("""{"result":"ok","time":1}""", "direct") }
    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.retryable)
  }

  @Test
  fun `error 是数组时也当服务端错误,把原话带出来`() {
    val error = assertThrowsNga { parseNgaJson("""{"error":["您的访问速度过快"]}""") }
    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals("您的访问速度过快", error.text)
  }
}
