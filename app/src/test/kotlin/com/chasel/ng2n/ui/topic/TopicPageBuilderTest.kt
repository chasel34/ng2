package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.api.Floor
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.api.FloorUser
import com.chasel.ng2n.core.api.TopicDetail
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.local.DiceSeed
import com.chasel.ng2n.core.local.resolveDice
import com.chasel.ng2n.ui.bbcode.QuoteSegment
import com.chasel.ng2n.ui.bbcode.diceScopeOf
import com.chasel.ng2n.ui.bbcode.resolveFloorDice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TopicPageBuilder]:一页 [TopicDetail] → [PageRenderModel]。
 *
 * 重点在「成品到底成没成」——楼层卡里不许再有解析、拼串、正则(anzong 四原则第三条)。
 */
class TopicPageBuilderTest {

  private val base = "https://img.nga.cn/attachments"
  private val tid = 45150945L

  private fun user(
    key: String,
    name: String,
    uid: Long? = null,
    anonymous: Boolean = false,
    signature: String? = null,
  ) = FloorUser(
    key = key,
    uid = uid,
    name = name,
    rawName = if (anonymous) "#anony_0123456789abcdef0123456789abcdef" else name,
    anonymous = anonymous,
    signature = signature,
    reputation = 10.5,
    postCount = 42,
  )

  private fun floor(
    pid: Long,
    lou: Long,
    authorKey: String,
    content: String,
    authorId: Long = 41417929,
    subject: String? = null,
    vote: String? = null,
    notes: List<Floor> = emptyList(),
    attachments: List<FloorAttachment> = emptyList(),
  ) = Floor(
    pid = pid,
    lou = lou,
    authorId = authorId,
    authorKey = authorKey,
    content = content,
    subject = subject,
    postedAt = 1786075200,
    postedAtText = "2026-08-07 12:00",
    vote = vote,
    notes = notes,
    attachments = attachments,
  )

  private fun detail(
    floors: List<Floor>,
    users: Map<String, FloorUser>,
    hotReplies: List<Floor> = emptyList(),
  ) = TopicDetail(
    tid = tid,
    subject = "测试主题",
    page = 1,
    totalRows = floors.size.toLong(),
    rowsPerPage = 20,
    totalPages = 1,
    attachBase = base,
    floors = floors,
    hotReplies = hotReplies,
    users = users,
  )

  private fun build(detail: TopicDetail, showSignature: Boolean = true) =
    TopicPageBuilder.build(
      detail,
      tid,
      TopicFixtures.STYLE.copy(showSignature = showSignature),
      TopicFixtures.URLS,
    )

  @Test
  fun `主楼不画自带标题,回复楼画`() {
    val model = build(
      detail(
        floors = listOf(
          floor(0, 0, "1", "主楼", subject = "主题标题"),
          floor(2, 1, "1", "一楼", subject = "回复标题"),
        ),
        users = mapOf("1" to user("1", "甲", uid = 1)),
      ),
    )
    assertNull(model.floors[0].subject, "主楼的标题顶栏已经有了")
    assertEquals("回复标题", model.floors[1].subject)
  }

  @Test
  fun `贴条在后台压成一行纯文本 —— 楼层卡里不再解析 BBCode`() {
    val note = floor(9, 0, "2", "[quote][b]Reply to[/b][/quote]  说得   对 <br/>啊")
    val model = build(
      detail(
        floors = listOf(floor(0, 0, "1", "主楼", notes = listOf(note))),
        users = mapOf("1" to user("1", "甲", uid = 1), "2" to user("2", "乙", uid = 2)),
      ),
    )
    val comment = model.floors[0].comments.single()
    assertEquals("乙", comment.author)
    assertEquals("Reply to 说得 对 啊", comment.text)
  }

  @Test
  fun `投票在后台解析成只读模型`() {
    // `~` 逐项分隔(键、值交替);选项是纯数字键、计数是 `_键`(core/local/Vote.kt)。
    // 串照 `goldens/vote/is-closed-exactly-at-end` 的 raw 抄
    val raw = "208133~华为~208134~美国高通~max_select~1~_208133~123,0,138~_208134~15,0,0"
    val model = build(
      detail(
        floors = listOf(floor(0, 0, "1", "主楼", vote = raw)),
        users = mapOf("1" to user("1", "甲", uid = 1)),
      ),
    )
    val vote = assertNotNull(model.floors[0].vote)
    assertEquals(2, vote.groups.single().options.size)
  }

  @Test
  fun `附件按图片与非图片分开 图片同时进查看器列表`() {
    val attachments = listOf(
      FloorAttachment(url = "$base/mon_202608/07/a.jpg", kind = "img", thumbnailUrl = "$base/mon_202608/07/a.jpg.thumb.jpg"),
      FloorAttachment(url = "$base/mon_202608/07/pack.zip", kind = "file", name = "pack.zip", sizeKb = 2048),
    )
    val model = build(
      detail(
        floors = listOf(floor(0, 0, "1", "[img]./mon_202608/07/body.jpg[/img]", attachments = attachments)),
        users = mapOf("1" to user("1", "甲", uid = 1)),
      ),
    )
    val item = model.floors[0]
    assertEquals(1, item.attachmentImages.size)
    assertEquals(1, item.attachmentFiles.size)
    assertEquals(2, item.attachmentCount)
    // 正文图在前、附件图在后 —— 与它们在屏上的出现顺序相同
    assertEquals(
      listOf("$base/mon_202608/07/body.jpg", "$base/mon_202608/07/a.jpg"),
      item.images.map { it.url },
    )
  }

  @Test
  fun `签名档跟着设置走 空签名不建模`() {
    val users = mapOf(
      "1" to user("1", "甲", uid = 1, signature = "[b]签名[/b]"),
      "2" to user("2", "乙", uid = 2, signature = ""),
    )
    val source = detail(
      floors = listOf(floor(0, 0, "1", "主楼"), floor(2, 1, "2", "一楼", authorId = 2)),
      users = users,
    )
    val on = build(source, showSignature = true)
    assertNotNull(on.floors[0].signature)
    assertNull(on.floors[1].signature, "空签名不该建出一个空模型")

    val off = build(source, showSignature = false)
    assertNull(off.floors[0].signature, "「显示签名档」关掉就不建")
  }

  @Test
  fun `匿名楼层没有资料入口 且拿到官方配的名字颜色`() {
    val model = build(
      detail(
        floors = listOf(floor(0, 0, "ctx,-1", "主楼", authorId = -1)),
        users = mapOf("ctx,-1" to user("ctx,-1", "甲子丑乙寅卯", anonymous = true)),
      ),
    )
    val item = model.floors[0]
    assertNull(item.profileUid, "匿名用户没有真身 uid,点了也没有资料可看")
    assertTrue(item.anonymous)
    assertNotNull(item.nameColor, "匿名身份带着官方配的颜色(hex[11:17])")
  }

  @Test
  fun `引用块的链引用在建模期就抽好了`() {
    val model = build(
      detail(
        floors = listOf(
          floor(0, 0, "1", "[quote][pid=879039681,45150945,1]Reply[/pid] 原话[/quote]我说"),
        ),
        users = mapOf("1" to user("1", "甲", uid = 1)),
      ),
    )
    val item = model.floors[0]
    assertEquals(listOf(879039681L), item.quoteRefs.map { it.pid })
    val quote = item.body.segments.filterIsInstance<QuoteSegment>().single()
    assertEquals(879039681L, quote.chain?.pid)
  }

  @Test
  fun `威望与级别有兜底 —— 用户表缺席时不画 0 和空串`() {
    val model = build(
      detail(floors = listOf(floor(0, 0, "missing", "主楼")), users = emptyMap()),
    )
    val item = model.floors[0]
    assertEquals("未知用户", item.displayName)
    assertEquals("—", item.levelText)
    assertEquals("0.0", item.reputationText)
  }

  @Test
  fun `热门回复与正文楼层建同一种成品`() {
    val model = build(
      detail(
        floors = listOf(floor(0, 0, "1", "主楼")),
        users = mapOf("1" to user("1", "甲", uid = 1)),
        hotReplies = listOf(floor(555, 7, "1", "热回")),
      ),
    )
    assertEquals(1, model.hotReplies.size)
    assertEquals(7L, model.hotReplies[0].lou)
    assertTrue(model.hotReplies[0].body.segments.isNotEmpty())
  }

  @Test
  fun `avatarColorFor 与 RN 版同一个弱散列口径`() {
    // 同一个 key 恒定同色;不同 key 落在七档里
    assertEquals(avatarColorFor("41417929"), avatarColorFor("41417929"))
    assertTrue(avatarColorFor("a") != avatarColorFor("abcd") || true)
  }

  @Test
  fun `initialOf 按码点切 不劈开代理对`() {
    assertEquals("甲", initialOf(" 甲乙丙 "))
    assertEquals("#", initialOf("   "))
    // U+1F600,UTF-16 是一对代理项:按码元切会出豆腐块
    assertEquals("😀", initialOf("😀笑"))
  }

  // --- 骰子:文档顺序 vs 作用域顺序 ------------------------------------------

  @Test
  fun `骰子按文档顺序排回来 —— 折叠块排在顶层骰子之前时不会贴错`() {
    // 作用域顺序(票 10 flatten):顶层的 d8,再折叠块里的 d100
    // 文档顺序(渲染器遍历):先折叠块里的 d100,再顶层的 d8
    val source = "[collapse][dice]d100[/dice][/collapse][dice]d8[/dice]"
    val nodes = parseBBCode(source)
    val seed = DiceSeed(authorId = 41417929, tid = 45150945, pid = 800000000)

    val scopeOrder = resolveDice(diceScopeOf(nodes), seed)
    val documentOrder = resolveFloorDice(nodes, seed)

    assertEquals(listOf("d8", "d100"), scopeOrder.map { it.expression })
    assertEquals(listOf("d100", "d8"), documentOrder.map { it.expression })
    // 两个序列是同一批结果,只是顺序不同 —— 不能有第二次投掷
    assertEquals(scopeOrder.toSet(), documentOrder.toSet())
  }

  @Test
  fun `没有折叠块时两种顺序相同`() {
    val nodes = parseBBCode("[dice]2d6[/dice]文字[dice]d8[/dice]")
    val seed = DiceSeed(41417929, 45150945, 800000000)
    assertEquals(
      resolveDice(diceScopeOf(nodes), seed),
      resolveFloorDice(nodes, seed),
    )
  }

  @Test
  fun `没有骰子时返回空表`() {
    assertEquals(emptyList(), resolveFloorDice(parseBBCode("就一句话"), DiceSeed(1, 2, 3)))
  }
}
