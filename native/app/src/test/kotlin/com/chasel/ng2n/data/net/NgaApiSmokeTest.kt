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

/**
 * 端点层的联网冒烟:真的打一次 NGA,看**请求装配**在真实服务端还认不认。
 *
 * ## 默认跳过 —— 这是整票唯一允许的跳过(同 `NgaIntegrationSmokeTest`)
 *
 * 失败可能与代码无关(没网、NGA 在限流、CI 出不去),让它进默认单测集会把「红」稀释掉。
 * 用 `Assume` 而不是 `@Ignore`:门控是运行时的环境变量,打开就真跑。
 *
 * ```bash
 * cd native
 * NGA_INTEGRATION=1 NGA_TEST_PROXY=127.0.0.1:7897 \
 *   ./gradlew :app:testDebugUnitTest --tests '*NgaApiSmokeTest' --rerun-tasks
 * ```
 *
 * ## 纪律
 *
 * - **游客态**(只读那几条不需要账号);
 * - **只断言形状**,不断言条数与内容 —— 那些每分钟都在变;
 * - **不要连续打**:NGA 会因为背靠背冷启动限流(T6)。所以四条只读端点串在**一个**
 *   用例里、每条之间静置 [COOLDOWN_MS];整个用例重跑之间请自行静置 ≥60s;
 * - **脱敏**:日志只打端点名与顶层键,不打 uid / token / fav 码 / 搜索词
 *   (修 P1-04 的同款纪律)。
 */
class NgaApiSmokeTest {

  /** 两发之间的静置。NGA 对背靠背请求会限流(见 research/perf-history)。 */
  private val cooldownMs = System.getenv("NGA_SMOKE_COOLDOWN_MS")?.toLongOrNull() ?: COOLDOWN_MS

  /** 搜索那一发前面的静置(它比普通列表更容易撞限流,见用例里的注释)。 */
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
      // 真机上这里是系统 WebView UA;JVM 里拿不到,用兜底常量
      userAgents = UserAgents.fallback(),
      comboCache = InMemoryComboCache(),
      readChain = NgaClient.defaultReadChain(listCredentials = { emptyList() }),
    )
  }

  @Test
  fun `游客态四条只读端点各打一次`() = runBlocking {
    assumeTrue("默认跳过;要跑请设 NGA_INTEGRATION=1", System.getenv("NGA_INTEGRATION") == "1")
    val nga = client()

    // ① thread.php:版块主题列表(fid=7 艾泽拉斯议事厅,游客可读)
    val list = fetchTopicList(nga, boardId = 7, kind = BoardKind.BOARD, page = 1)
    assertTrue(list.listStructure, "服务端没按主题列表回话")
    assertTrue(list.topics.isNotEmpty(), "第 1 页一条主题都没有")
    assertTrue(list.rowsPerPage > 0 && list.totalPages >= 1)
    // 中文没解坏(GBK 响应一路解码过来)
    assertTrue(list.topics.none { it.subject.contains('�') }, "标题里有替换字符")
    println("[smoke] thread.php topics=${list.topics.size} totalPages=${list.totalPages}")

    delay(cooldownMs)

    // ② read.php:帖子详情(spec §6 的对拍样例主题)
    val detail = fetchTopicDetail(nga, tid = SAMPLE_TID, page = 1)
    assertEquals(SAMPLE_TID, detail.tid)
    assertTrue(detail.floors.isNotEmpty(), "一层楼都没解出来")
    assertEquals(0L, detail.floors.first().lou, "第 1 页的第一条应该是主楼")
    // 附件域名必须是响应给的,不是兜底常量硬拼出来的
    assertTrue(Regex("^https://\\S+/attachments$").matches(detail.attachBase), detail.attachBase)
    assertEquals(
      detail.totalPages,
      Math.max(1, Math.ceil(detail.totalRows.toDouble() / detail.rowsPerPage).toInt()),
    )
    // 每个楼层都查得到作者
    assertTrue(detail.floors.all { detail.users.containsKey(it.authorKey) }, "有楼层查不到作者")
    println("[smoke] read.php floors=${detail.floors.size} totalPages=${detail.totalPages}")

    delay(cooldownMs)

    // ③ app_api.php:版块分类树(游客也能拿到完整的树)
    val tree = fetchBoardTree(nga)
    assertTrue(tree.categories.isNotEmpty(), "一个分类都没解出来")
    assertTrue(tree.categories.any { it.groups.any { group -> group.boards.isNotEmpty() } })
    println("[smoke] app_api categories=${tree.categories.size}")

    // 搜索比普通列表更容易撞限流:2026-08-22 本机实测,与前三发只隔 20s 时服务端回
    // `2048:service error`,静置一分钟后同一串字节(curl 对拍)照常有结果——
    // 是限流不是编码坏了,所以这一发前面多等一会儿
    delay(searchCooldownMs)

    // ④ thread.php?key=:主题搜索(**key 走 UTF-8**,与版块搜索的 GBK 是两套)
    val found = fetchTopicSearch(nga, key = "炉石", page = 1)
    assertTrue(found.topics.isNotEmpty(), "搜不到主题")
    assertTrue(found.topics.none { it.subject.contains('\uFFFD') }, "搜索结果标题里有替换字符")
    println("[smoke] thread.php?key topics=${found.topics.size} totalRows=${found.totalRows}")

    // 版块搜索(forum.php,**key 走 GBK**)**游客跑不了**:2026-08-22 本机 curl 对拍,
    // 同样的字节游客拿到 `{"error":{"0":"2048:必须登录才能使用此功能"}}` ——
    // 是服务端的规矩,不是编码坏了。它挪进了下面的登录态用例(待所有者)。
  }

  /**
   * 需要登录的只读端点。**默认跳过,且不在本票跑** —— 凭证要真人给(票面「真人介入」)。
   *
   * ```bash
   * NGA_INTEGRATION=1 NGA_UID=… NGA_CID=… ./gradlew :app:testDebugUnitTest --tests '*NgaApiSmokeTest'
   * ```
   */
  @Test
  fun `登录态只读端点(待所有者——需要 NGA_UID NGA_CID)`() = runBlocking {
    val credential = envCredential()
    assumeTrue("默认跳过;要跑请设 NGA_INTEGRATION=1 与 NGA_UID / NGA_CID", credential != null)
    val nga = client(credential)

    // 用户资料:这个接口**必须带 Referer**,少了服务端直接拒绝
    val profile = fetchUserProfile(nga, uid = credential!!.uid.toLong())
    assertEquals(credential.uid.toLong(), profile.uid)
    assertTrue(profile.name.isNotEmpty())
    // 脱敏:不打 uid / 名字,只报「拿到了」
    println("[smoke] ucp get ok, group=${profile.group ?: "-"}")

    delay(cooldownMs)

    // 收藏夹列表:登录态最轻的一个读端点
    val folders = fetchFavoriteFolders(nga)
    println("[smoke] topic_favor_v2 list_folder folders=${folders.size}")

    delay(cooldownMs)

    // 版块搜索:**key 走 GBK**,这一条错了服务端只会说「没找到」,所以它同时是
    // 「GBK 出站编码在真实服务端还认不认」的验收。游客不可用(见上一个用例的注释)
    val boards = fetchBoardSearch(nga, key = "炉石")
    assertTrue(boards.isNotEmpty(), "GBK key 搜不到版块(多半是编码那一步坏了)")
    assertTrue(boards.any { it.board.name.contains("炉石") })
    println("[smoke] forum.php boards=${boards.size}")
  }

  /**
   * 写端点的冒烟 —— **只写不跑,待所有者**。
   *
   * 每一条都会在真实账号上留下痕迹(签到、点赞、收藏、改签名、改屏蔽表),
   * 而且 P1-01 的闸决定了它们**失败不会重试**,跑坏了没有自动补偿。
   * 所以除了 `NGA_INTEGRATION=1`,还要显式 `NGA_WRITE_SMOKE=1` 才会真跑;
   * 由所有者在自己的账号上、按需一条条放开。
   *
   * ```bash
   * NGA_INTEGRATION=1 NGA_WRITE_SMOKE=1 NGA_UID=… NGA_CID=… \
   *   ./gradlew :app:testDebugUnitTest --tests '*NgaApiSmokeTest' --rerun-tasks
   * ```
   */
  @Test
  fun `写端点(待所有者——会在真实账号上留痕,需 NGA_WRITE_SMOKE=1)`() = runBlocking {
    val credential = envCredential()
    assumeTrue(
        "写端点冒烟默认不跑;要跑请设 NGA_WRITE_SMOKE=1 并给 NGA_UID / NGA_CID",
        credential != null && System.getenv("NGA_WRITE_SMOKE") == "1",
    )
    val nga = client(credential)
    val uid = credential!!.uid

    // ① 签到:最安全的一条(每天至多一次,重复了服务端自己回「今天已经签到」)
    val result: CheckInResult = checkIn(nga)
    println("[smoke] check_in already=${result.alreadyCheckedIn}")

    delay(cooldownMs)

    // ② 点赞 → 再点一次取消(切换式,两发之后回到原状)
    val liked = postRecommend(nga, tid = SAMPLE_TID, pid = 0, action = RecommendAction.LIKE)
    delay(cooldownMs)
    val undone = postRecommend(nga, tid = SAMPLE_TID, pid = 0, action = RecommendAction.LIKE)
    println("[smoke] topic_recommend ${liked.state} → ${undone.state}")

    delay(cooldownMs)

    // ③ 收藏 → 取消(注意取消用的是 tidarray)
    val folder = fetchFavoriteFolders(nga).firstOrNull { it.isDefault }
    if (folder != null) {
      addTopicFavorite(nga, tid = SAMPLE_TID, folderId = folder.id)
      delay(cooldownMs)
      removeTopicFavorite(nga, tid = SAMPLE_TID, folderId = folder.id)
      println("[smoke] topic_favor_v2 add/del ok")
    }

    delay(cooldownMs)

    // ④ 改签名:先读回原文再写回去,免得把所有者的签名弄没了
    val before = fetchUserProfile(nga, uid = uid.toLong()).signature ?: ""
    delay(cooldownMs)
    updateSignature(nga, uid = uid, signature = before)
    println("[smoke] set_sign 回写原签名 ok")

    // ⑤ 子版块订阅 / ⑥ 官方屏蔽词整表写回 / ⑦ 清空通知 —— **故意不自动跑**:
    // 前两个会改所有者的账号设置(且屏蔽词是整表覆盖,写错一次就把原表冲了),
    // 清空通知不可逆。所有者要验时,照下面的形状手动放开:
    //
    //   setSubBoardOption(nga, subBoard, parentFid = -7, action = SubBoardAction.SUBSCRIBE)
    //   setBlockWords(nga, uid = uid, list = fetchBlockWords(nga, uid))   // 先读后写,内容不变
    //   clearNotificationFeed(nga)
    println("[smoke] user_option / set_block_word / noti del 三条留给所有者手动放开")
  }

  private fun envCredential(): Credential? {
    if (System.getenv("NGA_INTEGRATION") != "1") return null
    val uid = System.getenv("NGA_UID")?.takeIf { it.isNotBlank() } ?: return null
    val cid = System.getenv("NGA_CID")?.takeIf { it.isNotBlank() } ?: return null
    return Credential(uid = uid, token = cid)
  }

  private companion object {
    /** spec §6 的对拍样例主题。 */
    const val SAMPLE_TID = 45150945L

    /** 两发之间静置多久。NGA 的限流窗口按分钟算,这里取一个不至于让单测跑一整天的值。 */
    const val COOLDOWN_MS = 20_000L

    /** 搜索前的静置。实测 20s 不够(会回 `2048:service error`)。 */
    const val SEARCH_COOLDOWN_MS = 70_000L
  }
}
