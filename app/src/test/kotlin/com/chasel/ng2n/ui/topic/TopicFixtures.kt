package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.DefaultAttachmentUrls
import com.chasel.ng2n.core.api.TopicPageSnapshot
import com.chasel.ng2n.core.net.FakeResponse
import com.chasel.ng2n.core.net.HttpRequest
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.testClient
import com.chasel.ng2n.core.net.utf8
import com.chasel.ng2n.ui.theme.LightColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import java.net.URI

/**
 * 票 13 单测共用的假件:一个按脚本吐 `read.php` 响应的 [NgaClient]。
 *
 * **假的是传输层不是端点层** —— 请求照常走票 06 的策略链、票 04 的清洗与信封、
 * 票 07 的 `parseTopicDetail`,所以「页转换」这条断言链是端到端的:
 * 字节 → 解码 → 清洗 → 解析 → [TopicPageBuilder] 建模。
 */
object TopicFixtures {

  val STYLE = TopicRenderStyle(
    colors = LightColors,
    bodyFontSize = 15.5f,
    bodyLineHeight = 1.68f,
    showSignature = true,
  )

  val URLS: AttachmentUrls = DefaultAttachmentUrls

  /**
   * 一页 `read.php` 的信封。
   *
   * @param floors 每一楼:`lou to 正文`
   * @param rows 楼层总数(`__ROWS`),总页数按它算
   */
  fun pageEnvelope(
    tid: Long = 45150945,
    page: Int = 1,
    floors: List<FloorSpec>,
    rows: Long = 20,
    rowsPerPage: Long = 20,
    subject: String = "测试主题",
    boardName: String? = "网事杂谈",
    hotReplies: List<FloorSpec> = emptyList(),
  ): String {
    val users = floors.plus(hotReplies).distinctBy { it.authorId }.joinToString(",") { spec ->
      """"${spec.authorId}":{"uid":${spec.authorId},"username":"${spec.authorName}",""" +
        """"memberid":39,"postnum":${spec.postCount},"rvrc":${spec.reputationRaw},""" +
        (spec.signature?.let { """"signature":"$it",""" } ?: "") +
        """"yz":4}"""
    }
    val rowsJson = floors.mapIndexed { index, spec -> """"$index":${spec.toJson(hotReplies)}""" }
      .joinToString(",")
    val board = boardName?.let { ""","__F":{"name":"$it"}""" } ?: ""
    return """{"data":{""" +
      """"__GLOBAL":{"_ATTACH_BASE_VIEW":"img.nga.cn/attachments"},""" +
      """"__U":{$users},""" +
      """"__R":{$rowsJson},""" +
      """"__T":{"tid":$tid,"subject":"$subject","author":"${floors.firstOrNull()?.authorName ?: ""}",""" +
      """"authorid":${floors.firstOrNull()?.authorId ?: 0}},""" +
      """"__PAGE":$page,"__ROWS":$rows,"__R__ROWS_PAGE":$rowsPerPage$board}}"""
  }

  data class FloorSpec(
    val pid: Long,
    val lou: Long,
    val authorId: Long,
    val authorName: String = "用户$authorId",
    val content: String = "第 $lou 楼",
    val score: Long = 0,
    val signature: String? = null,
    val postCount: Long = 100,
    val reputationRaw: Long = 105,
    val vote: String? = null,
    val alterInfo: String? = null,
    val comments: List<FloorSpec> = emptyList(),
  ) {
    fun toJson(hotReplies: List<FloorSpec> = emptyList()): String {
      val comment = if (comments.isEmpty()) "" else {
        ""","comment":{""" + comments.mapIndexed { index, spec ->
          """"$index":${spec.toJson()}"""
        }.joinToString(",") + "}"
      }
      val hot = if (lou != 0L || hotReplies.isEmpty()) "" else {
        ""","hotreply":{""" + hotReplies.mapIndexed { index, spec ->
          """"$index":${spec.toJson()}"""
        }.joinToString(",") + "}"
      }
      val voteField = vote?.let { ""","vote":"$it"""" } ?: ""
      val alter = alterInfo?.let { ""","alterinfo":"$it"""" } ?: ""
      return """{"pid":$pid,"lou":$lou,"authorid":$authorId,"content":"$content",""" +
        """"postdatetimestamp":1786075200,"postdate":"2026-08-07 12:00","score":$score,""" +
        """"from_client":"8 Android"$voteField$alter$comment$hot}"""
    }
  }

  /** 按 URL 里的 `page=` 分发响应;`onRequest` 可以记账或改行为。 */
  fun client(
    onRequest: ((HttpRequest) -> Unit)? = null,
    respond: (page: Int, uri: URI) -> FakeResponse,
  ): Pair<NgaClient, RecordingTransport> {
    val transport = RecordingTransport { request ->
      onRequest?.invoke(request)
      val uri = URI(request.url)
      val page = uri.rawQuery.orEmpty().split("&")
        .firstOrNull { it.startsWith("page=") }
        ?.removePrefix("page=")
        ?.toIntOrNull() ?: 1
      respond(page, uri)
    }
    return testClient(transport) to transport
  }

  fun okJson(body: String): FakeResponse =
    FakeResponse(status = 200, contentType = "text/javascript; charset=UTF-8", body = utf8(body))
}

/** 把存下来的快照记在内存里的假 sink。 */
class FakeSnapshotSink : TopicSnapshotSink {
  val saved = mutableListOf<TopicPageSnapshot>()
  override suspend fun save(snapshot: TopicPageSnapshot) {
    saved.add(snapshot)
  }
}

/** 单测里造 [TopicRepository] —— IoScope 用测试自己的 scope。 */
fun testRepository(
  client: NgaClient,
  scope: CoroutineScope,
  sink: TopicSnapshotSink = FakeSnapshotSink(),
  compute: CoroutineDispatcher = Dispatchers.Unconfined,
  io: CoroutineDispatcher = scope.testDispatcher(),
) = TopicRepository(
  client = client,
  cachePayloads = sink,
  scope = scope,
  compute = compute,
  io = io,
)

/**
 * 请求那一发的调度器(票 37)。默认跟传进来的 scope 用**同一个**——单测给的是
 * `StandardTestDispatcher(testScheduler)`,`advanceUntilIdle()` 才推得动 `withContext(io)`
 * 里的活;换成 `Dispatchers.IO` 就跑到真线程池上去了,虚拟时间管不着。
 */
fun CoroutineScope.testDispatcher(): CoroutineDispatcher =
  coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Unconfined
