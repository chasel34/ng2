package com.chasel.ng2n.data.favorites

import com.chasel.ng2n.core.api.FavoriteFolder
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.ok
import com.chasel.ng2n.core.net.testClient
import com.chasel.ng2n.data.account.FakePreferencesDataStore
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.settings.foldersOfTopic
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 收藏夹仓库 —— 重点是 **P1-02:两份缓存都按 uid 分桶**。
 *
 * RN 版 `store/topic-favor.ts:40-43` 的 TanStack Query key
 * (`['favorite-folders']` / `['favorite-topics', folderId]`)里**没有 uid**,
 * 切号之后进收藏夹页看到的还是上一个账号的夹与夹内主题;夹 id 在两个账号之间
 * 没有任何关系,撞车时更是拿旧账号的 id 去打新账号的接口(审计 P1-02)。
 */
class TopicFavoriteRepositoryTest {

  private fun settings() = SettingsStore(FakePreferencesDataStore())

  /** 一份夹列表响应。`default` 键存在的那个是默认夹。 */
  private fun foldersBody(vararg folders: String): String =
    """{"data":{"0":{${folders.withIndex().joinToString(",") { (i, f) -> "\"$i\":$f" }}}}}"""

  private fun folder(id: Int, name: String, length: Int = 0, isDefault: Boolean = false): String =
    """{"id":$id,"name":"$name","length":$length${if (isDefault) ",\"default\":1" else ""}}"""

  private fun topic(tid: Long): String =
    """{"tid":$tid,"subject":"帖 $tid","author":"作者","replies":1,"postdate":1787371200}"""

  private fun topicsBody(vararg rows: String, totalRows: Int = 100): String {
    val entries = rows.withIndex().joinToString(",") { (i, row) -> "\"$i\":$row" }
    return """{"data":{"__T":{$entries},"__F":{},"__ROWS":$totalRows,"__T__ROWS_PAGE":35}}"""
  }

  // ------------------------------------------------------------ P1-02

  @Test
  fun `两个账号的夹列表互不串 —— 修 P1-02 的一半`() = runTest {
    // 服务端按当前凭证给不同的夹;假 transport 只能按请求次数分,所以用一个计数器
    var call = 0
    val transport = RecordingTransport {
      call += 1
      if (call == 1) ok(foldersBody(folder(1, "甲的夹"))) else ok(foldersBody(folder(1, "乙的夹")))
    }
    val repo = TopicFavoriteRepository(testClient(transport), settings())

    repo.ensureFolders("1001")
    repo.ensureFolders("1002")

    assertEquals(listOf("甲的夹"), repo.foldersOf("1001").folders.map { it.name })
    assertEquals(listOf("乙的夹"), repo.foldersOf("1002").folders.map { it.name })
  }

  @Test
  fun `夹 id 撞车时夹内主题也不串 —— 桶键是 uid 加夹 id`() = runTest {
    var call = 0
    val transport = RecordingTransport { request ->
      if (request.url.contains("thread.php")) {
        call += 1
        if (call == 1) ok(topicsBody(topic(111))) else ok(topicsBody(topic(222)))
      } else {
        ok(foldersBody(folder(7, "夹")))
      }
    }
    val repo = TopicFavoriteRepository(testClient(transport), settings())

    // 两个账号各有一个 id 都是 7 的夹(审计 P1-02 点名的「相同 folder ID 碰撞」)
    repo.ensureTopics("1001", 7)
    repo.ensureTopics("1002", 7)

    assertEquals(listOf(111L), repo.topicsOf("1001", 7).topics.map { it.tid })
    assertEquals(listOf(222L), repo.topicsOf("1002", 7).topics.map { it.tid })
  }

  @Test
  fun `游客态不发请求 —— 接口对游客一律回「你必须先登录论坛」`() = runTest {
    val transport = RecordingTransport { ok(foldersBody()) }
    val repo = TopicFavoriteRepository(testClient(transport), settings())

    repo.ensureFolders(null)
    repo.ensureTopics(null, 7)

    assertTrue(transport.requests.isEmpty())
    assertTrue(repo.foldersOf(null).folders.isEmpty())
    assertTrue(repo.foldersOf(null).loaded, "游客态是确定的空,不该一直转圈")
  }

  // ------------------------------------------------------------ 翻页与索引

  @Test
  fun `翻页拼在一起 并按 tid 去重`() = runTest {
    var call = 0
    val transport = RecordingTransport {
      call += 1
      if (call == 1) ok(topicsBody(topic(1), topic(2))) else ok(topicsBody(topic(2), topic(3)))
    }
    val repo = TopicFavoriteRepository(testClient(transport), settings())

    repo.ensureTopics("1001", 7)
    repo.loadNextTopicPage("1001", 7)

    assertEquals(listOf(1L, 2L, 3L), repo.topicsOf("1001", 7).topics.map { it.tid })
  }

  @Test
  fun `翻到的每一页顺手喂给归属索引`() = runTest {
    val store = settings()
    val transport = RecordingTransport { ok(topicsBody(topic(111), topic(222))) }
    val repo = TopicFavoriteRepository(testClient(transport), store)

    repo.ensureTopics("1001", 7)

    val index = store.currentTopicFavorIndex("1001")
    assertEquals(listOf(7), foldersOfTopic(index, 111))
    assertEquals(listOf(7), foldersOfTopic(index, 222))
    // 索引是按 uid 分键的:另一个账号看不到
    assertEquals(emptyList(), foldersOfTopic(store.currentTopicFavorIndex("1002"), 111))
  }

  @Test
  fun `整个夹只有一页时才敢反过来清本机记错的归属`() = runTest {
    val store = settings()
    var call = 0
    val transport = RecordingTransport {
      call += 1
      // 第一次:两页(totalRows 100),第二次:一页(totalRows 1)
      if (call == 1) {
        ok(topicsBody(topic(111), topic(222)))
      } else {
        ok(topicsBody(topic(111), totalRows = 1))
      }
    }
    val repo = TopicFavoriteRepository(testClient(transport), store)

    repo.ensureTopics("1001", 7)
    assertEquals(listOf(7), foldersOfTopic(store.currentTopicFavorIndex("1001"), 222))

    repo.refreshTopics("1001", 7)
    assertEquals(
      emptyList(),
      foldersOfTopic(store.currentTopicFavorIndex("1001"), 222),
      "整个夹都在手上了,记着却没出现的那条是过期记录",
    )
  }

  // ------------------------------------------------------------ 夹的增删改

  @Test
  fun `写完必重拉夹列表 —— 计数与默认徽标以服务端为准`() = runTest {
    var call = 0
    val transport = RecordingTransport { request ->
      when {
        request.url.contains("modify_folder") -> ok("""{"data":{"0":"操作成功"}}""")
        else -> {
          call += 1
          if (call == 1) {
            ok(foldersBody(folder(1, "甲", length = 3), folder(2, "乙", isDefault = true)))
          } else {
            ok(foldersBody(folder(1, "甲", length = 3, isDefault = true), folder(2, "乙")))
          }
        }
      }
    }
    val repo = TopicFavoriteRepository(testClient(transport), settings())

    repo.ensureFolders("1001")
    assertEquals(2L, repo.foldersOf("1001").folders.first { it.isDefault }.id)

    repo.modifyFolder("1001", folderId = 1, name = "甲", asDefault = true)

    assertEquals(1L, repo.foldersOf("1001").folders.first { it.isDefault }.id)
  }

  @Test
  fun `改一个夹之后 它的主题列表连同已翻的页一起丢掉`() = runTest {
    val transport = RecordingTransport { request ->
      when {
        request.url.contains("thread.php") -> ok(topicsBody(topic(1)))
        request.url.contains("modify_folder") -> ok("""{"data":{"0":"操作成功"}}""")
        else -> ok(foldersBody(folder(7, "夹")))
      }
    }
    val repo = TopicFavoriteRepository(testClient(transport), settings())

    repo.ensureTopics("1001", 7)
    assertTrue(repo.topicsOf("1001", 7).pages.isNotEmpty())

    repo.modifyFolder("1001", folderId = 7, name = "改了名")

    assertTrue(repo.topicsOf("1001", 7).pages.isEmpty(), "下次进去要从第一页重取")
  }

  @Test
  fun `删夹之后本机索引里那个夹被摘干净`() = runTest {
    val store = settings()
    var folderCall = 0
    val transport = RecordingTransport { request ->
      when {
        request.url.contains("thread.php") -> ok(topicsBody(topic(111)))
        request.url.contains("del_folder") -> ok("""{"data":{"0":"操作成功"}}""")
        else -> {
          folderCall += 1
          // 删之前两个夹,删之后只剩 3
          if (folderCall == 1) {
            ok(foldersBody(folder(7, "要删的"), folder(3, "留着的")))
          } else {
            ok(foldersBody(folder(3, "留着的")))
          }
        }
      }
    }
    val repo = TopicFavoriteRepository(testClient(transport), store)

    repo.ensureFolders("1001")
    repo.ensureTopics("1001", 7)
    assertEquals(listOf(7), foldersOfTopic(store.currentTopicFavorIndex("1001"), 111))

    repo.deleteFolder("1001", 7)

    assertEquals(emptyList(), foldersOfTopic(store.currentTopicFavorIndex("1001"), 111))
  }

  @Test
  fun `多选对话框的差集逐个串行发 —— 不并发`() = runTest {
    val transport = RecordingTransport { request ->
      when {
        request.url.contains("list_folder") -> ok(foldersBody(folder(3, "甲"), folder(7, "乙")))
        else -> ok("""{"data":{"0":"操作成功"}}""")
      }
    }
    val store = settings()
    val repo = TopicFavoriteRepository(testClient(transport), store)

    repo.applyTopicFavorites("1001", tid = 555, added = listOf(3), removed = listOf(7))

    val writes = transport.requests.filter { it.url.contains("topic_favor_v2") }
    // add / del / 善后重拉的 list_folder
    assertTrue(writes.any { it.url.contains("__act=add") })
    assertTrue(writes.any { it.url.contains("__act=del&") || it.url.endsWith("__act=del") })
    assertEquals(listOf(3), foldersOfTopic(store.currentTopicFavorIndex("1001"), 555))
  }

  @Test
  fun `取消收藏用的是 tidarray 不是 tid`() = runTest {
    val transport = RecordingTransport { ok("""{"data":{"0":"操作成功"}}""") }
    val repo = TopicFavoriteRepository(testClient(transport), settings())

    repo.applyTopicFavorites("1001", tid = 555, added = emptyList(), removed = listOf(7))

    val del = transport.requests.first { it.url.contains("__act=del") }
    val body = del.body?.toString(Charsets.UTF_8).orEmpty()
    assertTrue(body.contains("tidarray=555"), body)
    assertFalse(body.contains("&tid=") || body.startsWith("tid="), body)
  }

  // ------------------------------------------------------------ pickFavoriteFolder

  @Test
  fun `没手动选过就落在默认夹`() {
    val folders = listOf(
      FavoriteFolder(id = 1, name = "甲"),
      FavoriteFolder(id = 2, name = "乙", isDefault = true),
    )
    assertEquals(2L, pickFavoriteFolder(folders, pickedId = null)?.id)
    assertEquals(1L, pickFavoriteFolder(folders, pickedId = 1)?.id)
  }

  @Test
  fun `服务端没标默认就退到第一个夹`() {
    val folders = listOf(FavoriteFolder(id = 5, name = "甲"), FavoriteFolder(id = 6, name = "乙"))
    assertEquals(5L, pickFavoriteFolder(folders, pickedId = null)?.id)
    // 选过的夹被删掉之后同样退回默认/第一个,不会留在一个不存在的夹上
    assertEquals(5L, pickFavoriteFolder(folders, pickedId = 999)?.id)
  }

  @Test
  fun `一个夹都没有时是 null`() {
    assertNull(pickFavoriteFolder(emptyList(), pickedId = 3))
  }
}
