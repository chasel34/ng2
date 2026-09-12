package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.assertThrowsNga
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadEndpointsTest {

  @Test
  fun `普通版块传 fid、合集传 stid(二选一)`() = runTest {
    val board = ApiFixture(THREAD_OK)
    fetchTopicList(board.client, boardId = -7, kind = BoardKind.BOARD, page = 1)
    assertTrue(board.url().contains("/thread.php?"))
    assertEquals("-7", board.query()["fid"])
    assertNull(board.query()["stid"])

    val collection = ApiFixture(THREAD_OK)
    fetchTopicList(collection.client, boardId = 44618580, kind = BoardKind.COLLECTION, page = 1)
    assertEquals("44618580", collection.query()["stid"])
    assertNull(collection.query()["fid"])
  }

  @Test
  fun `按发帖时间排序才带 order_by,默认(最后回复)不带`() = runTest {
    val sorted = ApiFixture(THREAD_OK)
    fetchTopicList(sorted.client, -7, BoardKind.BOARD, page = 3, sort = TopicSort.POST_DATE)
    assertEquals("postdatedesc", sorted.query()["order_by"])
    assertEquals("3", sorted.query()["page"])

    val plain = ApiFixture(THREAD_OK)
    fetchTopicList(plain.client, -7, BoardKind.BOARD, page = 1)
    assertNull(plain.query()["order_by"])
    assertNull(plain.query()["recommend"])
  }

  @Test
  fun `精华区带 recommend=1 与 Android 同款的固定参数,sort 不生效`() = runTest {
    val fixture = ApiFixture(THREAD_OK)
    fetchTopicList(
      fixture.client,
      -7,
      BoardKind.BOARD,
      page = 1,
      sort = TopicSort.LAST_POST,
      recommend = true,
    )
    assertEquals("1", fixture.query()["recommend"])
    assertEquals("postdatedesc", fixture.query()["order_by"])
    assertEquals("1", fixture.query()["user"])
  }

  @Test
  fun `响应里没有 data 时报解析错,交给上层兜底`() = runTest {
    val fixture = ApiFixture("""{"data":"","time":1}""")
    val error = assertThrowsNga { fetchTopicList(fixture.client, -7, BoardKind.BOARD, page = 1) }
    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.text.contains("没有 data"))
  }

  @Test
  fun `data 在但不是主题列表的形状时报解析错,而不是「这个版块是空的」`() = runTest {
    val fixture = ApiFixture("""{"data":{"__CU":{"uid":10000001}},"time":1}""")
    val error = assertThrowsNga { fetchTopicList(fixture.client, -7, BoardKind.BOARD, page = 1) }
    assertTrue(error.text.contains("没有主题列表结构"))
  }

  @Test
  fun `真的空版块(__T 是空对象)仍然是成功的 0 条`() = runTest {
    val fixture = ApiFixture("""{"data":{"__T":{},"__F":{"fid":-7,"name":"网事杂谈"},"__ROWS":0},"time":1}""")
    val list = fetchTopicList(fixture.client, -7, BoardKind.BOARD, page = 1)
    assertEquals(emptyList(), list.topics)
    assertTrue(list.listStructure)
  }

  @Test
  fun `某人的主题走 authorid,回复再加 searchpost=1`() = runTest {
    val topics = ApiFixture(THREAD_OK)
    fetchUserTopics(topics.client, uid = 41417929, kind = UserPostKind.TOPICS, page = 1)
    assertTrue(topics.url().contains("/thread.php?"))
    assertEquals("41417929", topics.query()["authorid"])
    assertNull(topics.query()["searchpost"])

    val replies = ApiFixture(THREAD_OK)
    fetchUserTopics(replies.client, uid = 41417929, kind = UserPostKind.REPLIES, page = 2)
    assertEquals("1", replies.query()["searchpost"])
    assertEquals("2", replies.query()["page"])
  }

  @Test
  fun `翻过头是「到底了」而不是报错——假错误归一成空页`() = runTest {
    val fixture = ApiFixture(NO_RESULT)
    val list = fetchUserTopics(fixture.client, uid = 1, kind = UserPostKind.REPLIES, page = 500)
    assertEquals(emptyList(), list.topics)
    assertEquals(false, hasMoreUserPosts(list))
    assertEquals(false, list.listStructure)

    val noData = ApiFixture(NO_RESULT_NO_DATA)
    val empty = fetchUserTopics(noData.client, uid = 1, kind = UserPostKind.REPLIES, page = 500)
    assertTrue(empty.listStructure)
  }

  @Test
  fun `收藏夹的主题列表走 thread_php favor=夹id`() = runTest {
    val fixture = ApiFixture(THREAD_OK)
    fetchFavoriteTopics(fixture.client, folderId = 4699990, page = 1)
    assertTrue(fixture.url().contains("/thread.php?"))
    assertEquals("4699990", fixture.query()["favor"])
    assertEquals("1", fixture.query()["page"])
  }

  @Test
  fun `thread_php 的 key 按 UTF-8 编码并保留 inchst 声明`() = runTest {
    val fixture = ApiFixture(THREAD_OK)
    fetchTopicSearch(fixture.client, key = "炉石", page = 1)
    assertTrue(fixture.url().contains("/thread.php?"))
    assertTrue(fixture.url().contains("key=%E7%82%89%E7%9F%B3"))
    assertTrue(fixture.url().contains("__inchst=UTF8"))
  }

  @Test
  fun `forum_php 的 key 按 GBK 编码,且不再声明 inchst`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":{"fid":422,"name":"炉石传说"}},"time":1}""")
    fetchBoardSearch(fixture.client, key = "炉石")
    assertTrue(fixture.url().contains("/forum.php?"))
    assertTrue(fixture.url().contains("key=%C2%AF%CA%AF"))
    assertTrue(!fixture.url().contains("__inchst"))
  }

  @Test
  fun `主题搜索的参数组合——全站 本版 合集 含正文`() = runTest {
    val all = ApiFixture(THREAD_OK)
    fetchTopicSearch(all.client, key = "炉石", page = 1)
    assertNull(all.query()["fid"])
    assertNull(all.query()["stid"])
    assertNull(all.query()["content"])

    val board = ApiFixture(THREAD_OK)
    fetchTopicSearch(board.client, key = "炉石", page = 3, boardId = -7, kind = BoardKind.BOARD, searchContent = true)
    assertEquals("-7", board.query()["fid"])
    assertNull(board.query()["stid"])
    assertEquals("1", board.query()["content"])
    assertEquals("3", board.query()["page"])

    val collection = ApiFixture(THREAD_OK)
    fetchTopicSearch(collection.client, key = "炉石", page = 1, boardId = 31576766, kind = BoardKind.COLLECTION)
    assertEquals("31576766", collection.query()["stid"])
    assertNull(collection.query()["fid"])
  }

  @Test
  fun `搜索没有结果时假错误归一成空页 空列表,而不是抛`() = runTest {
    val topics = ApiFixture(NO_RESULT_NO_DATA)
    assertEquals(emptyList(), fetchTopicSearch(topics.client, key = "x", page = 99).topics)

    val boards = ApiFixture("""{"error":{"0":"没找到符合条件的版面"},"time":1}""")
    assertEquals(emptyList(), fetchBoardSearch(boards.client, key = "不存在"))
  }

  @Test
  fun `服务端提示行(denied 且没有作者)不进搜索结果`() = runTest {
    val fixture = ApiFixture(
      """{"data":{"__T":{"0":{"tid":1,"subject":"正常帖","author":"nga_user","authorid":42},
      "1":{"tid":2,"subject":"帐号权限不足","author":"","authorid":0,"denied":"1"}},
      "__ROWS":2,"__T__ROWS_PAGE":35},"time":1}""",
    )
    val list = fetchTopicSearch(fixture.client, key = "x", page = 1)
    assertEquals(listOf(1L), list.topics.map { it.tid })
    assertEquals(2L, list.totalRows)
  }

  @Test
  fun `分类树打的是 app_api home category,并声明 bare 信封`() = runTest {
    val fixture = ApiFixture(
      """{"data":{"0":{"_id":"wow","name":"魔兽世界","groups":{"0":{"id":1,"name":"组",
      "forums":{"0":{"fid":7,"name":"艾泽拉斯议事厅"}}}}}},"other":{}}""",
    )
    val tree = fetchBoardTree(fixture.client)
    assertTrue(fixture.url().contains("/app_api.php?"))
    assertEquals("home", fixture.query()["__lib"])
    assertEquals("category", fixture.query()["__act"])
    assertEquals("8", fixture.query()["__output"])
    assertEquals(listOf("魔兽世界"), tree.categories.map { it.name })
  }

  @Test
  fun `帖子详情带 tid page v2,fav 与过滤参数按需出现`() = runTest {
    val plain = ApiFixture(READ_OK)
    fetchTopicDetail(plain.client, tid = 45150945, page = 2)
    assertTrue(plain.url().contains("/read.php?"))
    assertEquals("45150945", plain.query()["tid"])
    assertEquals("2", plain.query()["page"])
    assertEquals("1", plain.query()["v2"])
    assertNull(plain.query()["fav"])
    assertNull(plain.query()["pid"])
    assertNull(plain.query()["authorid"])

    val filtered = ApiFixture(READ_OK)
    fetchTopicDetail(filtered.client, tid = 1, page = 1, favCode = "abc123", pid = 9, authorId = 205511)
    assertEquals("abc123", filtered.query()["fav"])
    assertEquals("9", filtered.query()["pid"])
    assertEquals("205511", filtered.query()["authorid"])
  }

  @Test
  fun `通知 get_all 打对端点`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":""},"time":1}""")
    val feed = fetchNotificationFeed(fixture.client)
    assertTrue(fixture.url().contains("/nuke.php?"))
    assertEquals("noti", fixture.query()["__lib"])
    assertEquals("get_all", fixture.query()["__act"])
    assertEquals(emptyList(), feed.items)
  }

  @Test
  fun `版块收藏列表走 form action=get`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":{"0":{"id":-7,"fid":-7,"name":"网事杂谈"}}},"time":1}""")
    val boards = fetchBoardFavorites(fixture.client)
    assertEquals("forum_favor2", fixture.query()["__lib"])
    assertEquals("forum_favor", fixture.query()["__act"])
    assertEquals("get", fixture.form()["action"])
    assertEquals(listOf(-7L), boards.map { it.id })
  }

  @Test
  fun `收藏夹列表打 topic_favor_v2 list_folder,响应没有 data 时报解析错`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":{"0":{"id":7,"name":"夹"}}},"time":1}""")
    fetchFavoriteFolders(fixture.client)
    assertEquals("topic_favor_v2", fixture.query()["__lib"])
    assertEquals("list_folder", fixture.query()["__act"])
    assertEquals("1", fixture.query()["page"])

    val broken = ApiFixture("""{"data":"","time":1}""")
    val error = assertThrowsNga { fetchFavoriteFolders(broken.client) }
    assertTrue(error.text.contains("没有 data"))
  }

  @Test
  fun `用户资料必带 Referer,且跟着当前 host 走`() = runTest {
    val fixture = ApiFixture(UCP_OK)
    fetchUserProfile(fixture.client, uid = 41417929)
    assertEquals("https://bbs.nga.cn/nuke.php?func=ucp", fixture.referer())
    assertEquals("ucp", fixture.query()["__lib"])
    assertEquals("get", fixture.query()["__act"])
    assertEquals("41417929", fixture.query()["uid"])
  }

  @Test
  fun `按用户名查走同一个端点,名字按 UTF-8 进 query`() = runTest {
    val fixture = ApiFixture(UCP_OK)
    fetchUserProfileByName(fixture.client, username = "冷面比面筋好吃")
    assertEquals("冷面比面筋好吃", fixture.query()["username"])
    assertNull(fixture.query()["uid"])
    assertTrue(fixture.referer()!!.contains("nuke.php?func=ucp"))
  }

  @Test
  fun `查无此人——假错误「找不到用户」要靠 data 为空认出来并报原话`() = runTest {
    val fixture = ApiFixture("""{"error":{"0":"找不到用户"},"time":1}""")
    val error = assertThrowsNga { fetchUserProfile(fixture.client, uid = 999999999) }
    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertTrue(error.text.contains("找不到用户"))
  }

  @Test
  fun `data 在 user 不在——和「查无此人」分开报,排障时看得出差别`() = runTest {
    val fixture = ApiFixture("""{"data":{},"time":1}""")
    val error = assertThrowsNga { fetchUserProfile(fixture.client, uid = 1) }
    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.text.contains("资料响应里没有用户"))
  }

  @Test
  fun `头像补充查询——URL 直接躺在 data 的 0 上,拿不到就是 null`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":"https://img.nga.cn/avatars/a.jpg"},"time":1}""")
    assertEquals("https://img.nga.cn/avatars/a.jpg", fetchUserAvatar(fixture.client, uid = 1))
    assertEquals("get_avatar", fixture.query()["__act"])
    assertEquals("https://bbs.nga.cn/nuke.php?func=ucp", fixture.referer())

    val empty = ApiFixture("""{"data":{"0":""},"time":1}""")
    assertNull(fetchUserAvatar(empty.client, uid = 1))
  }

  @Test
  fun `官方屏蔽词读取带 uid 与带 uid 的 Referer`() = runTest {
    val fixture = ApiFixture("""{"data":{"0":"1\r\n加密货币 私聊出\r\n42/gerraerd"},"time":1}""")
    val list = fetchBlockWords(fixture.client, uid = "42")
    assertEquals("get_block_word", fixture.query()["__act"])
    assertEquals("42", fixture.query()["uid"])
    assertEquals("https://bbs.nga.cn/nuke.php?func=ucp&uid=42", fixture.referer())
    assertEquals(listOf("加密货币", "私聊出"), list.words)
  }

  @Test
  fun `热帖并发拉前 N 页,页码 1 到 N`() = runTest {
    val fixture = ApiFixture(THREAD_OK)
    val result = fetchHotTopicPages(fixture.client, boardId = -7, kind = BoardKind.BOARD, pages = 3)
    assertEquals(3, result.pages.size)
    assertEquals(3, result.pagesTried)
    assertEquals(emptyList(), result.failedPages)
    assertEquals(
      listOf("1", "2", "3"),
      fixture.requests.map { it.url.substringAfter("page=").substringBefore("&") }.sorted(),
    )
  }

  @Test
  fun `热帖页数不传时用默认档`() = runTest {
    val fixture = ApiFixture(THREAD_OK)
    assertEquals(
      DEFAULT_HOT_PAGES,
      fetchHotTopicPages(fixture.client, -7, BoardKind.BOARD).pagesTried,
    )
  }

  @Test
  fun `热帖聚合按 24h 窗口过滤并按回复数排序`() = runTest {
    val fixture = ApiFixture(
      """{"data":{"__T":{
      "0":{"tid":1,"subject":"新帖少回复","author":"a","authorid":1,"replies":3,"postdate":1786090000,"lastpost":1786099000},
      "1":{"tid":2,"subject":"新帖多回复","author":"b","authorid":2,"replies":30,"postdate":1786080000,"lastpost":1786099500},
      "2":{"tid":3,"subject":"老坟被顶","author":"c","authorid":3,"replies":300,"postdate":1700000000,"lastpost":1786099900}
      },"__ROWS":3,"__T__ROWS_PAGE":35},"time":1}""",
    )
    val hot = fetchHotTopics(
      fixture.client,
      boardId = -7,
      kind = BoardKind.BOARD,
      nowSeconds = 1786100000,
      pages = 1,
    )
    assertEquals(listOf(2L, 1L), hot.topics.map { it.tid })
    assertEquals(emptyList(), hot.failedPages)
  }
}

private const val THREAD_OK =
  """{"data":{"__T":{"0":{"tid":45150945,"subject":"测试","author":"nga_user","authorid":10000001,
  "replies":1,"postdate":1758210953,"lastpost":1774011037}},"__F":{"fid":-7,"name":"网事杂谈"},
  "__ROWS":1,"__T__ROWS_PAGE":35},"time":1}"""

private const val NO_RESULT =
  """{"error":{"0":"2048:没有符合条件的结果"},"data":{"__MESSAGE":{"0":2048}},"time":1}"""

private const val NO_RESULT_NO_DATA = """{"error":{"0":"2048:没有符合条件的结果"},"time":1}"""

private const val READ_OK =
  """{"data":{"__T":{"tid":45150945,"subject":"测试","author":"nga_user","authorid":10000001},
  "__R":{"0":{"pid":0,"lou":0,"authorid":10000001,"content":"正文","postdate":"2025-09-18 23:55"}},
  "__U":{"10000001":{"uid":10000001,"username":"nga_user"}},
  "__ROWS":1,"__R__ROWS_PAGE":20},"time":1}"""

private const val UCP_OK =
  """{"data":{"0":{"uid":41417929,"username":"BugenZhao","posts":2277,"rvrc":15}},"time":1}"""
