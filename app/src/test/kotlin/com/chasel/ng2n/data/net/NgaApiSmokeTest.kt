package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.CheckInResult
import com.chasel.ng2n.core.api.RecommendAction
import com.chasel.ng2n.core.api.addTopicFavorite
import com.chasel.ng2n.core.api.checkIn
import com.chasel.ng2n.core.api.fetchBoardSearch
import com.chasel.ng2n.core.api.fetchBoardTree
import com.chasel.ng2n.core.api.fetchFavoriteFolders
import com.chasel.ng2n.core.api.fetchTopicDetail
import com.chasel.ng2n.core.api.fetchTopicList
import com.chasel.ng2n.core.api.fetchTopicSearch
import com.chasel.ng2n.core.api.fetchUserProfile
import com.chasel.ng2n.core.api.postRecommend
import com.chasel.ng2n.core.api.removeTopicFavorite
import com.chasel.ng2n.core.api.setSubBoardOption
import com.chasel.ng2n.core.api.updateSignature
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.core.net.InMemoryComboCache
import com.chasel.ng2n.core.net.NetworkSettingsSource
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.UserAgents
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NgaApiSmokeTest {

  private val cooldownMs = System.getenv("NGA_SMOKE_COOLDOWN_MS")?.toLongOrNull() ?: COOLDOWN_MS

  private val searchCooldownMs =
    System.getenv("NGA_SMOKE_SEARCH_COOLDOWN_MS")?.toLongOrNull() ?: SEARCH_COOLDOWN_MS

  private fun proxy(): Proxy {
    val spec = System.getenv("NGA_TEST_PROXY") ?: return Proxy.NO_PROXY
    val (host, port) = spec.substringAfter("://").split(":")
    return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port.toInt()))
  }

  private fun client(credential: Credential? = null): NgaClient {
    val http: OkHttpClient = ngaHttpClientBuilder().proxy(proxy()).build()
    return NgaClient(
      transports = OkHttpTransportFactory(http),
      credentials = object : CredentialSource {
        override suspend fun current(): Credential? = credential
        override suspend fun all(): List<Credential> = listOfNotNull(credential)
      },
      settings = NetworkSettingsSource.defaults(),
      userAgents = UserAgents.fallback(),
      comboCache = InMemoryComboCache(),
      readChain = NgaClient.defaultReadChain(listCredentials = { emptyList() }),
    )
  }

  @Test
  fun `游客态四条只读端点各打一次`() = runBlocking {
    assumeTrue("默认跳过;要跑请设 NGA_INTEGRATION=1", System.getenv("NGA_INTEGRATION") == "1")
    val nga = client()

    val list = fetchTopicList(nga, boardId = 7, kind = BoardKind.BOARD, page = 1)
    assertTrue(list.listStructure, "服务端没按主题列表回话")
    assertTrue(list.topics.isNotEmpty(), "第 1 页一条主题都没有")
    assertTrue(list.rowsPerPage > 0 && list.totalPages >= 1)
    assertTrue(list.topics.none { it.subject.contains('�') }, "标题里有替换字符")
    println("[smoke] thread.php topics=${list.topics.size} totalPages=${list.totalPages}")

    delay(cooldownMs)

    val detail = fetchTopicDetail(nga, tid = SAMPLE_TID, page = 1)
    assertEquals(SAMPLE_TID, detail.tid)
    assertTrue(detail.floors.isNotEmpty(), "一层楼都没解出来")
    assertEquals(0L, detail.floors.first().lou, "第 1 页的第一条应该是主楼")
    assertTrue(Regex("^https://\\S+/attachments$").matches(detail.attachBase), detail.attachBase)
    assertEquals(
      detail.totalPages,
      Math.max(1, Math.ceil(detail.totalRows.toDouble() / detail.rowsPerPage).toInt()),
    )
    assertTrue(detail.floors.all { detail.users.containsKey(it.authorKey) }, "有楼层查不到作者")
    println("[smoke] read.php floors=${detail.floors.size} totalPages=${detail.totalPages}")

    delay(cooldownMs)

    val tree = fetchBoardTree(nga)
    assertTrue(tree.categories.isNotEmpty(), "一个分类都没解出来")
    assertTrue(tree.categories.any { it.groups.any { group -> group.boards.isNotEmpty() } })
    println("[smoke] app_api categories=${tree.categories.size}")

    delay(searchCooldownMs)

    val found = fetchTopicSearch(nga, key = "炉石", page = 1)
    assertTrue(found.topics.isNotEmpty(), "搜不到主题")
    assertTrue(found.topics.none { it.subject.contains('\uFFFD') }, "搜索结果标题里有替换字符")
    println("[smoke] thread.php?key topics=${found.topics.size} totalRows=${found.totalRows}")

  }

  @Test
  fun `登录态只读端点(待所有者——需要 NGA_UID NGA_CID)`() = runBlocking {
    val credential = envCredential()
    assumeTrue("默认跳过;要跑请设 NGA_INTEGRATION=1 与 NGA_UID / NGA_CID", credential != null)
    val nga = client(credential)

    val profile = fetchUserProfile(nga, uid = credential!!.uid.toLong())
    assertEquals(credential.uid.toLong(), profile.uid)
    assertTrue(profile.name.isNotEmpty())
    println("[smoke] ucp get ok, group=${profile.group ?: "-"}")

    delay(cooldownMs)

    val folders = fetchFavoriteFolders(nga)
    println("[smoke] topic_favor_v2 list_folder folders=${folders.size}")

    delay(cooldownMs)

    val boards = fetchBoardSearch(nga, key = "炉石")
    assertTrue(boards.isNotEmpty(), "GBK key 搜不到版块(多半是编码那一步坏了)")
    assertTrue(boards.any { it.board.name.contains("炉石") })
    println("[smoke] forum.php boards=${boards.size}")
  }

  @Test
  fun `写端点(待所有者——会在真实账号上留痕,需 NGA_WRITE_SMOKE=1)`() = runBlocking {
    val credential = envCredential()
    assumeTrue(
        "写端点冒烟默认不跑;要跑请设 NGA_WRITE_SMOKE=1 并给 NGA_UID / NGA_CID",
        credential != null && System.getenv("NGA_WRITE_SMOKE") == "1",
    )
    val nga = client(credential)
    val uid = credential!!.uid

    val result: CheckInResult = checkIn(nga)
    println("[smoke] check_in already=${result.alreadyCheckedIn}")

    delay(cooldownMs)

    val liked = postRecommend(nga, tid = SAMPLE_TID, pid = 0, action = RecommendAction.LIKE)
    delay(cooldownMs)
    val undone = postRecommend(nga, tid = SAMPLE_TID, pid = 0, action = RecommendAction.LIKE)
    println("[smoke] topic_recommend ${liked.state} → ${undone.state}")

    delay(cooldownMs)

    val folder = fetchFavoriteFolders(nga).firstOrNull { it.isDefault }
    if (folder != null) {
      addTopicFavorite(nga, tid = SAMPLE_TID, folderId = folder.id)
      delay(cooldownMs)
      removeTopicFavorite(nga, tid = SAMPLE_TID, folderId = folder.id)
      println("[smoke] topic_favor_v2 add/del ok")
    }

    delay(cooldownMs)

    val before = fetchUserProfile(nga, uid = uid.toLong()).signature ?: ""
    delay(cooldownMs)
    updateSignature(nga, uid = uid, signature = before)
    println("[smoke] set_sign 回写原签名 ok")

    println("[smoke] user_option / set_block_word / noti del 三条留给所有者手动放开")
  }

  private fun envCredential(): Credential? {
    if (System.getenv("NGA_INTEGRATION") != "1") return null
    val uid = System.getenv("NGA_UID")?.takeIf { it.isNotBlank() } ?: return null
    val cid = System.getenv("NGA_CID")?.takeIf { it.isNotBlank() } ?: return null
    return Credential(uid = uid, token = cid)
  }

  private companion object {
    const val SAMPLE_TID = 45150945L

    const val COOLDOWN_MS = 20_000L

    const val SEARCH_COOLDOWN_MS = 70_000L
  }
}
