package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.ALICE
import com.chasel.ng2n.core.net.assertThrowsNga
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteEndpointsTest {

  @Test
  fun `赞踩打 nuke_php topic_recommend add,value 与 pid 都在 query 里`() = runTest {
    val like = ApiFixture("""{"data":{"1":1},"time":1}""")
    val result = postRecommend(like.client, tid = 45150945, pid = 123456, action = RecommendAction.LIKE)
    assertTrue(like.url().contains("/nuke.php?"))
    assertEquals("topic_recommend", like.query()["__lib"])
    assertEquals("add", like.query()["__act"])
    assertEquals("1", like.query()["value"])
    assertEquals("45150945", like.query()["tid"])
    assertEquals("123456", like.query()["pid"])
    assertEquals(RecommendResult(RecommendState.LIKED, 1), result)

    val dislike = ApiFixture("""{"data":{"1":-1},"time":1}""")
    val down = postRecommend(dislike.client, tid = 1, pid = 2, action = RecommendAction.DISLIKE)
    assertEquals("-1", dislike.query()["value"])
    assertEquals(RecommendResult(RecommendState.DISLIKED, -1), down)
  }

  @Test
  fun `主楼 pid=0 必须真的出现在参数里,不能被空值剔除规则吃掉`() = runTest {
    val fixture = ApiFixture("""{"data":{"1":1},"time":1}""")
    postRecommend(fixture.client, tid = 45150945, pid = 0, action = RecommendAction.LIKE)
    assertEquals("0", fixture.query()["pid"])
  }

  @Test
  fun `delta 也可能在 data 的 0 上,字符串数字一并收下，挖不出来才报解析错`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":"2"},"time":1}""")
    assertEquals(
      RecommendResult(RecommendState.LIKED, 2),
      postRecommend(fixture.client, 1, 2, RecommendAction.LIKE),
    )

    val broken = ApiFixture("""{"data":{"0":"操作成功"},"time":1}""")
    val error = assertThrowsNga { postRecommend(broken.client, 1, 2, RecommendAction.LIKE) }
    assertTrue(error.text.contains("分数增量"))
  }

  @Test
  fun `切换式状态迁移与服务端 delta 语义互相一致`() {
    val cases = listOf(
      Triple(RecommendState.NONE, RecommendAction.LIKE, RecommendState.LIKED) to 1L,
      Triple(RecommendState.LIKED, RecommendAction.LIKE, RecommendState.NONE) to -1L,
      Triple(RecommendState.DISLIKED, RecommendAction.LIKE, RecommendState.LIKED) to 2L,
      Triple(RecommendState.NONE, RecommendAction.DISLIKE, RecommendState.DISLIKED) to -1L,
      Triple(RecommendState.DISLIKED, RecommendAction.DISLIKE, RecommendState.NONE) to 1L,
      Triple(RecommendState.LIKED, RecommendAction.DISLIKE, RecommendState.DISLIKED) to -2L,
    )
    for ((transition, delta) in cases) {
      val (current, action, next) = transition
      assertEquals(next, nextRecommendState(current, action))
      assertEquals(delta, expectedRecommendDelta(current, action))
      assertEquals(next, recommendStateOf(action, delta))
    }
    assertEquals(RecommendState.NONE, recommendStateOf(RecommendAction.LIKE, 0))
    assertEquals(RecommendState.NONE, recommendStateOf(RecommendAction.DISLIKE, 0))
  }

  @Test
  fun `版块收藏 add del 走 form,合集也把 stid 当 fid 传`() = runTest {
    val add = ApiFixture()
    addBoardFavorite(add.client, boardId = -7)
    assertEquals("add", add.form()["action"])
    assertEquals("-7", add.form()["fid"])

    val collection = ApiFixture()
    addBoardFavorite(collection.client, boardId = 31576766)
    assertEquals("31576766", collection.form()["fid"])

    val del = ApiFixture()
    removeBoardFavorite(del.client, boardId = -7)
    assertEquals("del", del.form()["action"])
  }

  @Test
  fun `重复收藏的 server 错误「你已经收藏了这个版面」吞掉当成功`() = runTest {
    val fixture = ApiFixture("""{"error":{"0":"你已经收藏了这个版面"},"time":1}""")
    addBoardFavorite(fixture.client, boardId = -7)
    assertEquals(1, fixture.requests.size)
  }

  @Test
  fun `清空收藏——先 get 再逐个 del,返回删掉的列表供撤销`() = runTest {
    val fixture = ApiFixture(
      """{"data":{"0":{"0":{"id":31576766,"stid":31576766,"name":"联运网页游戏"},
      "1":{"id":-7,"fid":-7,"name":"网事杂谈"}}},"time":1}""",
      ApiFixture.OK_TEXT,
      ApiFixture.OK_TEXT,
    )
    val removed = clearBoardFavorites(fixture.client)
    assertEquals(listOf(31576766L, -7L), removed.map { it.id })
    assertEquals(3, fixture.requests.size)
    assertEquals("get", fixture.form(0)["action"])
    assertEquals("del", fixture.form(1)["action"])
    assertEquals("31576766", fixture.form(1)["fid"])
    assertEquals("del", fixture.form(2)["action"])
    assertEquals("-7", fixture.form(2)["fid"])
  }

  @Test
  fun `空收藏时一次 del 都不发`() = runTest {
    val fixture = ApiFixture("""{"data":{},"time":1}""")
    assertEquals(emptyList(), clearBoardFavorites(fixture.client))
    assertEquals(1, fixture.requests.size)
  }

  @Test
  fun `收藏 add 带 tid,取消 del 的参数名是 tidarray 不是 tid`() = runTest {
    val add = ApiFixture()
    addTopicFavorite(add.client, tid = 45150945, folderId = 7)
    assertEquals("add", add.query()["__act"])
    assertEquals("45150945", add.form()["tid"])
    assertEquals("7", add.form()["folder"])

    val del = ApiFixture()
    removeTopicFavorite(del.client, tid = 45150945, folderId = 7)
    assertEquals("del", del.query()["__act"])
    assertEquals("45150945", del.form()["tidarray"])
    assertNull(del.form()["tid"])
    assertEquals("7", del.form()["folder"])
  }

  @Test
  fun `new_folder 带 raw=3,opt 按是否设默认取 2 或 0,新夹 id 在 data 的 1 上`() = runTest {
    val plain = ApiFixture("""{"data":{"0":"操作成功","1":4699991},"time":1}""")
    assertEquals(4699991L, createFavoriteFolder(plain.client, name = "装机"))
    assertEquals("new_folder", plain.query()["__act"])
    assertEquals("3", plain.query()["raw"])
    assertEquals("0", plain.form()["opt"])

    val asDefault = ApiFixture()
    createFavoriteFolder(asDefault.client, name = "装机", asDefault = true)
    assertEquals("2", asDefault.form()["opt"])
  }

  @Test
  fun `modify_folder 重命名与设默认同一个 act,name 始终要带，del_folder 只带 folder`() = runTest {
    val rename = ApiFixture()
    modifyFavoriteFolder(rename.client, folderId = 7, name = "新名字")
    assertEquals("modify_folder", rename.query()["__act"])
    assertEquals("7", rename.form()["folder"])
    assertEquals("新名字", rename.form()["name"])

    val setDefault = ApiFixture()
    modifyFavoriteFolder(setDefault.client, folderId = 7, name = "现名", asDefault = true)
    assertEquals("2", setDefault.form()["opt"])

    val delete = ApiFixture()
    deleteFavoriteFolder(delete.client, folderId = 7)
    assertEquals("del_folder", delete.query()["__act"])
    assertEquals("3", delete.query()["raw"])
    assertEquals("7", delete.form()["folder"])
  }

  @Test
  fun `服务端语义错误原样抛出(envelope 兜底)`() = runTest {
    val fixture = ApiFixture("""{"error":{"0":"收藏夹数量已达上限"},"time":1}""")
    val error = assertThrowsNga { createFavoriteFolder(fixture.client, name = "第 21 个") }
    assertTrue(error.text.contains("收藏夹数量已达上限"))
  }

  @Test
  fun `签到打 check_in,没有额外参数，首次成功带上服务端原话`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":"签到成功，获得 12 个铜币"},"time":1}""")
    val result = checkIn(fixture.client)
    assertTrue(fixture.url().contains("/nuke.php?"))
    assertEquals("check_in", fixture.query()["__lib"])
    assertEquals("check_in", fixture.query()["__act"])
    assertEquals(CheckInResult(alreadyCheckedIn = false, message = "签到成功，获得 12 个铜币"), result)
  }

  @Test
  fun `「今天已经签到」是假错误,按成功处理并标出来，未登录这类真错误照抛`() = runTest {
    val again = ApiFixture("""{"error":{"0":"你今天已经签到过了"},"time":1}""")
    assertEquals(
      CheckInResult(alreadyCheckedIn = true, message = "你今天已经签到过了"),
      checkIn(again.client),
    )

    val guest = ApiFixture("""{"error":{"0":"你必须先登录论坛"},"time":1}""")
    assertThrowsNga { checkIn(guest.client) }
  }

  @Test
  fun `订阅 type=1 的子版块——query 带 del=filterId,form 带父 fid type info`() = runTest {
    val fixture = ApiFixture()
    setSubBoardOption(fixture.client, TID_SUB_BOARD, parentFid = -7, action = SubBoardAction.SUBSCRIBE)
    assertEquals("user_option", fixture.query()["__lib"])
    assertEquals("set", fixture.query()["__act"])
    assertEquals("12700430", fixture.query()["del"])
    assertNull(fixture.query()["add"])
    assertEquals("-7", fixture.form()["fid"])
    assertEquals("1", fixture.form()["type"])
    assertEquals("add_to_block_tids", fixture.form()["info"])
  }

  @Test
  fun `屏蔽同一个子版块换成 add，type=0 的子版块整个反过来`() = runTest {
    val block = ApiFixture()
    setSubBoardOption(block.client, TID_SUB_BOARD, parentFid = -7, action = SubBoardAction.BLOCK)
    assertEquals("12700430", block.query()["add"])
    assertNull(block.query()["del"])

    val inverted = ApiFixture()
    setSubBoardOption(inverted.client, FID_SUB_BOARD, parentFid = -7, action = SubBoardAction.SUBSCRIBE)
    assertEquals("414", inverted.query()["add"])
    assertNull(inverted.query()["del"])
    assertEquals("0", inverted.form()["type"])
  }

  @Test
  fun `改签名走 form,emoji 转成 UTF-16 十进制实体,清空传一个空格`() = runTest {
    val plain = ApiFixture()
    updateSignature(plain.client, uid = "42", signature = "一行签名")
    assertEquals("set_sign", plain.query()["__lib"])
    assertEquals("set", plain.query()["__act"])
    assertEquals("42", plain.form()["uid"])
    assertEquals("一行签名", plain.form()["sign"])

    val emoji = ApiFixture()
    updateSignature(emoji.client, uid = "42", signature = "A😂B")
    assertEquals("A&#55357;&#56834;B", emoji.form()["sign"])

    val cleared = ApiFixture()
    updateSignature(cleared.client, uid = "42", signature = "")
    assertEquals(" ", cleared.form()["sign"])
  }

  @Test
  fun `屏蔽表整表写回——data 按 GBK 编码进 query,并撤掉 inchst 声明`() = runTest {
    val fixture = ApiFixture()
    setBlockWords(
      fixture.client,
      uid = "42",
      list = BlockWordList(words = listOf("加密货币", "测试"), users = listOf(BlockedUser(42, "张三"))),
    )
    assertEquals("set_block_word", fixture.query()["__act"])
    assertTrue(
      fixture.url().contains("data=1%0D%0A%BC%D3%C3%DC%BB%F5%B1%D2%20%B2%E2%CA%D4%0D%0A42%2F%D5%C5%C8%FD"),
      "实际 URL:${fixture.url()}",
    )
    assertTrue(!fixture.url().contains("__inchst"))
    assertEquals("https://bbs.nga.cn/nuke.php?func=ucp&uid=42", fixture.referer())
  }

  @Test
  fun `清空屏蔽表写的是空串的两行,不是「不传 data」`() = runTest {
    val fixture = ApiFixture()
    setBlockWords(fixture.client, uid = "42", list = EMPTY_BLOCK_WORDS)
    assertTrue(fixture.url().contains("data=1%0D%0A%0D%0A"))
  }

  @Test
  fun `清空通知走 raw=3 的 del`() = runTest {
    val fixture = ApiFixture()
    clearNotificationFeed(fixture.client)
    assertEquals("noti", fixture.query()["__lib"])
    assertEquals("3", fixture.query()["raw"])
    assertEquals("del", fixture.query()["__act"])
  }

  @Test
  fun `八个写端点在整条链上都只发一次、不换账号`() = runTest {
    val writes: List<Pair<String, suspend (BlockedChainFixture) -> Unit>> = listOf(
      "topic_recommend" to { f -> postRecommend(f.client, 1, 0, RecommendAction.LIKE) },
      "forum_favor2" to { f -> removeBoardFavorite(f.client, -7) },
      "topic_favor_v2" to { f -> addTopicFavorite(f.client, 1, 7) },
      "check_in" to { f -> checkIn(f.client) },
      "user_option" to { f ->
        setSubBoardOption(f.client, TID_SUB_BOARD, -7, SubBoardAction.SUBSCRIBE)
      },
      "set_sign" to { f -> updateSignature(f.client, "42", "签名") },
      "set_block_word" to { f -> setBlockWords(f.client, "42", EMPTY_BLOCK_WORDS) },
      "noti del" to { f -> clearNotificationFeed(f.client) },
    )

    for ((name, write) in writes) {
      val fixture = BlockedChainFixture()
      assertThrowsNga { write(fixture) }
      assertEquals(1, fixture.attempts, "$name 是写操作,必须只发一次(修 P1-01)")
      assertEquals(listOf(ALICE.uid), fixture.uids, "$name 是写操作,不许换账号")
    }
  }

  @Test
  fun `对照组——同样被封时读端点会轮换,证明上一条不是链没跑`() = runTest {
    val fixture = BlockedChainFixture()
    assertThrowsNga { fetchTopicList(fixture.client, -7, BoardKind.BOARD, page = 1) }
    assertTrue(fixture.attempts > 1, "读操作应当轮换,实际只发了 ${fixture.attempts} 次")
  }
}

private val TID_SUB_BOARD = SubBoard(
  id = 570,
  kind = BoardKind.BOARD,
  fid = 570,
  name = "优惠信息 购物指南",
  filterId = 12700430,
  filterType = 1,
  attributes = 4654,
)

private val FID_SUB_BOARD = SubBoard(
  id = 414,
  kind = BoardKind.BOARD,
  fid = 414,
  name = "游戏综合讨论",
  filterId = 414,
  filterType = 0,
  attributes = 40,
)
