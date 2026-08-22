package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐条移植自 `src/core/net/combo-poison.test.ts`(4 + 2 = 6 条,全部移植)。
 *
 * 「一次瞬时失败把 thread.php 永久钉死在坏组合上」的回归测试
 * (2026-08-13,M4 走查里的「冷启动后第 4 个版块起全部显示『这个版块还没有主题』」)。
 *
 * 当时的链路:某次请求撞上限流 → `format-rotation` 换下一个组合 → 那个组合返回了一份
 * **能洗成 JSON 但没有 `__T`** 的东西 → 被当成「成功」记进组合缓存(key 是接口粒度的
 * `thread.php`,版块 / 搜索 / 收藏夹 / 热帖共用一条)→ 此后每个请求第一发就「成功」,
 * 永远轮不到别的组合,0 条主题一路走到 UI 变成空态,**只有杀进程能复位**。
 *
 * 这个文件钉住修完之后的三条性质:
 * 1. 形状不对的响应不是成功 —— 链会继续轮换,坏组合进不了缓存;
 * 2. 全都拿不到主题列表时是**报错**,不是「这个版块是空的」;
 * 3. 缓存有保质期,过期后会重新试探默认组合(自愈,不必杀进程)。
 *
 * ## 与 TS 版的差异
 *
 * TS 那边走的是 `fetchTopicList`(端点层,归票 07)。这里把它的**一票否决器**
 * `rejectNonTopicList` 原样写在测试里([REJECT_NON_TOPIC_LIST]):被测的是链怎么对待
 * 「调用方说这不是我要的东西」,与端点层怎么解字段无关。票 07 落地后端点层用的是同一条判据。
 */
class ComboPoisonTest {

  /**
   * `thread.php` 的形状判据:`__T` / `__F` / `__ROWS` 至少有一个
   * (**空版块也带这些键**,所以它区分得开「没帖」与「没拿到」)。
   */
  private val rejectNonTopicList: (NgaEnvelope) -> String? = { envelope ->
    val data = envelope.data as? JsonObject
    if (data != null && listOf("__T", "__F", "__ROWS").any { data.containsKey(it) }) {
      null
    } else {
      "响应里没有主题列表结构"
    }
  }

  /** 一页正常的主题列表。 */
  private val good = """{"data":{"__T":{"0":{"tid":1,"subject":"标题","author":"a"}},""" +
    """"__F":{"fid":650,"name":"原神"},"__ROWS":100,"__T__ROWS_PAGE":35},"time":1}"""

  /** 真的空版块:服务端照样给 `__T` / `__F` / `__ROWS`,只是一条都没有。 */
  private val emptyBoard =
    """{"data":{"__T":{},"__F":{"fid":650,"name":"原神"},"__ROWS":0,"__T__ROWS_PAGE":35},"time":1}"""

  /** 事故里那个形态:能解析,但根本不是一页主题列表。 */
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
    // `__output=8@bbs` 只在第 4 个版块那一次被封(模拟一次限流),之后立刻恢复;
    // 别的组合永远只给「能解析但没有 __T」的东西
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

    // 第一发就被封 → 轮换 → lite=js 拿到没有 __T 的东西(不算成功)→ 继续换域名 → 通了
    assertEquals(1, topicCount(client.execute(boardRequest(414))))

    // 缓存里记下的是真正给出了主题列表的那个组合,不是那个「能解析」的
    assertEquals(ResponseFormat.JSON, cache.get("thread.php")?.format)

    // 之后每个版块都正常 —— 事故里从这里开始全是空的
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
    // 一个都不许进缓存:进了就等于把下一次也钉死在这儿
    assertNull(cache.get("thread.php"))
    // 而且真的把组合空间跑完了(不是第一发就当成功收工)
    assertTrue(transport.combos().toSet().size > 1)
  }

  @Test
  fun `真的空版块仍然是「成功的 0 条」,不报错`() = runTest {
    val cache = InMemoryComboCache()
    val (client, _) = clientWith(cache) { emptyBoard }

    val result = client.execute(boardRequest(650))

    assertEquals(0, topicCount(result))
    // 服务端确实按主题列表回了话 —— UI 靠它区分「没帖」和「没拿到」
    assertTrue((result.data as JsonObject).containsKey("__T"))
    assertEquals(ResponseFormat.JSON, cache.get("thread.php")?.format)
  }

  @Test
  fun `缓存过期后会重新试探默认组合(自愈,不必杀进程)`() = runTest {
    var clock = 1_000_000L
    val cache = InMemoryComboCache(ttlMs = 60_000) { clock }
    // 默认域名一开始是通的,中途整个被封(格式换了也没用),之后又恢复
    var blockDefault = false
    val (client, transport) = clientWith(cache) { combo ->
      if (blockDefault && combo.endsWith("@https://bbs.nga.cn")) BLOCKED_HTML else good
    }

    client.execute(boardRequest(650))
    assertEquals("https://bbs.nga.cn", cache.get("thread.php")?.host)

    // 被封 → 换到镜像域名并记住它
    blockDefault = true
    client.execute(boardRequest(321))
    assertEquals("https://ngabbs.com", cache.get("thread.php")?.host)

    // 封解除了,但缓存还在保质期内:继续用镜像域名,一次都不回头试
    blockDefault = false
    transport.clear()
    client.execute(boardRequest(436))
    assertEquals(listOf("__output=8@https://ngabbs.com"), transport.combos())

    // 过了保质期:从默认组合重新试探
    clock += 60_001
    transport.clear()
    client.execute(boardRequest(414))
    assertEquals("__output=8@https://bbs.nga.cn", transport.combos().first())
    assertEquals("https://bbs.nga.cn", cache.get("thread.php")?.host)
  }

  // ── 「能解析」不等于「拿到了想要的东西」 ────────────────────────────────────

  @Test
  fun `顶层既没有 data 也没有 error 时报解析错,不是一份空数据`() {
    // 事故的必要条件之一:以前这里会把整个顶层当 data
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
