package com.chasel.ng2n.core.net.web

import com.chasel.ng2n.core.net.NgaErrorThrowDescriber
import com.chasel.ng2n.golden.Goldens
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `web` domain 全量对拍(7 条,票 08)。
 *
 * `input.text` 是 `read.php` **不带格式参数**拿回来的整页 HTML(已过 `decodeResponseBody`),
 * `expected` 是信封的 `data`——与 `__output=8` 同构。`root` 故意不进期望值:
 * 它就是 `{ data }`(理由同 `envelope` domain)。
 *
 * 抛错那两类的 `retryable` 必须对上,它决定反封锁链走不走下去(ADR-0002 第 6 条):
 * `<!--msgcodestart-->` 的服务端语义错误**不可重试**,一楼都没反解出来**可重试**。
 */
class ReadHtmlGoldenTest {

  @Test
  fun `web 金样本全量对拍`() = runGoldenDomain("web") {
    throwsDescribedBy(NgaErrorThrowDescriber)
    fn("parseReadPageHtml") { case ->
      parseReadPageHtml(case.stringField("text"), case.stringFieldOrNull("via")).data ?: JsonNull
    }
  }
}

/**
 * 手工移植自 `src/core/net/web/read-html.test.ts` 的语义断言。
 *
 * 金样本那条只会说「有 N 处差异」,说不出**差的是哪件事**;这里按票面的四类
 * (楼层元数据 / 用户表 / 分页 / msgcode 错误)各留一条有名字的回归线,
 * 反解跑偏时错误信息直接指到人话上。
 *
 * 语料复用金样本的 `input.text`(Kotlin 侧 classpath 上只有金样本,没有 `.gbk.bin`)。
 */
class ReadHtmlTest {

  private fun htmlOf(case: String): String =
    Goldens.load("web").first { it.name == case }.stringField("text")

  private fun dataOf(case: String): JsonObject =
    parseReadPageHtml(htmlOf(case), "web-fallback").data as JsonObject

  private fun obj(value: Any?): JsonObject = value as JsonObject

  private fun str(record: JsonObject, key: String): String? =
    (record[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun num(record: JsonObject, key: String): Double? =
    (record[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

  // ── 楼层元数据 ─────────────────────────────────────────────────────────────

  @Test
  fun `楼层按页内序号排成 __R,键与 JSON 路线一致`() {
    val rows = obj(dataOf("anonymous-hot-reply")["__R"])

    assertEquals((0 until 19).map { it.toString() }, rows.keys.toList())
  }

  @Test
  fun `主楼 pid 楼号 匿名作者 时间 赞数 标题 发帖设备都反解得出来`() {
    val main = obj(obj(dataOf("anonymous-hot-reply")["__R"])["0"])

    assertEquals(0.0, num(main, "pid"))
    assertEquals(0.0, num(main, "lou"))
    // 匿名楼层的 authorid 是页内序号而不是 uid(API 文档 §3)
    assertEquals("-1", str(main, "authorid"))
    assertEquals(1770802621.0, num(main, "postdatetimestamp"))
    assertEquals("2026-02-11 17:37", str(main, "postdate"))
    assertTrue((num(main, "score") ?: 0.0) > 0.0)
    assertEquals("天塌了，结婚四年，才知道老婆有精神分裂病史，并且复发", str(main, "subject"))
    assertEquals("8 Android", str(main, "from_client"))
  }

  @Test
  fun `正文原样取网页里那段 innerHTML,与 JSON 的 content 同口径`() {
    val rows = obj(dataOf("anonymous-hot-reply")["__R"])

    // 同一页 __output=8 响应里这一楼的 content 是
    // `[新婚夜…] 信源 [url]https://…&amp;wfr=spider&amp;for=pc[/url]`——两边逐字相同
    val floor = obj(rows["4"])
    assertTrue(str(floor, "content")!!.contains("[url]https://baijiahao.baidu.com/"))
    assertTrue(str(floor, "content")!!.contains("&amp;wfr=spider"))
    assertTrue(str(obj(rows["1"]), "content")!!.contains("<br/>"))
  }

  @Test
  fun `热门回复挂在主楼上,楼号按 pid 从本页认回来`() {
    val hot = obj(obj(obj(dataOf("anonymous-hot-reply")["__R"])["0"])["hotreply"])

    assertEquals(4, hot.size)
    val first = obj(hot["0"])
    assertEquals(857843067.0, num(first, "pid"))
    // 这条热门回复就是本页第 5 楼;网页版没直接给楼号,是按 pid 对回来的
    assertEquals(5.0, num(first, "lou"))
  }

  @Test
  fun `贴条挂在被贴的那一楼下面(不像 JSON 那样另占一条幽灵行)`() {
    val rows = obj(dataOf("comment")["__R"])
    val withNotes = rows.values.map(::obj).filter { it["comment"] != null }

    assertEquals(listOf(4.0), withNotes.map { num(it, "lou") })
    val note = obj(obj(withNotes[0]["comment"])["0"])
    assertEquals(824921555.0, num(note, "pid"))
    assertTrue(str(note, "content")!!.contains("是恩基爱社区"))
  }

  @Test
  fun `附件与编辑记录 attach load 与 loadAlertInfo 补回 attachs 与 alterinfo`() {
    val main = obj(obj(dataOf("attachments")["__R"])["0"])
    val attachs = obj(main["attachs"])

    assertEquals(2, attachs.size)
    val first = obj(attachs["0"])
    assertEquals("mon_202608/07/c4Q58-hy2fZcT1kShs-12d.jpg", str(first, "attachurl"))
    assertEquals("img", str(first, "type"))
    assertEquals("56", str(first, "thumb"))
    assertEquals("118", str(first, "size"))
    assertTrue(str(main, "alterinfo")!!.contains("[E1786103835"))
  }

  @Test
  fun `附件域名补上网页版省掉的那段路径`() {
    val global = obj(dataOf("attachments")["__GLOBAL"])

    // 网页里只有域名,JSON 里带 /attachments
    assertEquals("img.nga.cn/attachments", str(global, "_ATTACH_BASE_VIEW"))
  }

  // ── 用户表 ─────────────────────────────────────────────────────────────────

  @Test
  fun `实名用户带 uid 用户名 头像 发帖数,与 JSON 的 __U 同构`() {
    val user = obj(obj(dataOf("anonymous-hot-reply")["__U"])["66313282"])

    assertEquals(66313282.0, num(user, "uid"))
    assertEquals("两袖清风徐阁老", str(user, "username"))
    assertEquals(4370.0, num(user, "postnum"))
    assertEquals(42.0, num(user, "memberid"))
  }

  @Test
  fun `匿名槽位与 __GROUPS 等附表照收`() {
    val users = obj(dataOf("anonymous-hot-reply")["__U"])

    assertTrue(str(obj(users["-1"]), "username")!!.startsWith("#anony_"))
    assertEquals("警告等级1", str(obj(obj(users["__GROUPS"])["42"]), "0"))
    assertTrue(users["__MEDALS"] != null)
  }

  @Test
  fun `主题元数据 tid 标题 版块名 楼主`() {
    val data = dataOf("anonymous-hot-reply")
    val topic = obj(data["__T"])

    assertEquals(46186286.0, num(topic, "tid"))
    assertEquals("天塌了，结婚四年，才知道老婆有精神分裂病史，并且复发", str(topic, "subject"))
    // 匿名楼主:setDefault 给的是页内序号 -3,认人只能靠主楼作者那一串 #anony_
    assertEquals(-3.0, num(topic, "authorid"))
    assertTrue(str(topic, "author")!!.startsWith("#anony_"))
    assertEquals("晴风村", str(obj(data["__F"]), "name"))
  }

  @Test
  fun `实名楼主从用户表里取回名字`() {
    val topic = obj(dataOf("attachments")["__T"])

    assertEquals(37374391.0, num(topic, "authorid"))
    assertEquals("平雪飞", str(topic, "author"))
  }

  // ── 分页 ───────────────────────────────────────────────────────────────────

  @Test
  fun `当前页 每页楼数 总楼数都对得上 JSON 路线`() {
    val data = dataOf("anonymous-hot-reply")

    assertEquals(1.0, num(data, "__PAGE"))
    assertEquals(20.0, num(data, "__R__ROWS_PAGE"))
    // setDefault 给的 replies=283 → __ROWS=284,正是同一时刻 __output=8 响应里的值
    assertEquals(284.0, num(data, "__ROWS"))
  }

  @Test
  fun `另一份样本的总楼数同样等于 replies 加一`() {
    assertEquals(107.0, num(dataOf("attachments"), "__ROWS"))
  }

  // ── 2026-08-22 重验样本 ────────────────────────────────────────────────────

  @Test
  fun `重验样本 实名楼主 无附件 无嵌套 主楼带 subject`() {
    val data = dataOf("revalidate-45150945")
    val rows = obj(data["__R"])
    val main = obj(rows["0"])

    assertEquals(20, rows.size)
    assertEquals(45150945.0, num(obj(data["__T"]), "tid"))
    assertEquals("41417929", str(main, "authorid"))
    assertEquals("BugenZhao", str(obj(data["__T"]), "author"))
    // 旧客户端的 from_client 长这样,别把它当空值
    assertEquals("0 /", str(main, "from_client"))
    assertTrue(main["attachs"] == null)
    assertTrue(main["comment"] == null && main["hotreply"] == null)
  }
}
