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

object TopicFixtures {

  val STYLE = TopicRenderStyle(
    colors = LightColors,
    bodyFontSize = 15.5f,
    bodyLineHeight = 1.68f,
    showSignature = true,
  )

  val URLS: AttachmentUrls = DefaultAttachmentUrls

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

class FakeSnapshotSink : TopicSnapshotSink {
  val saved = mutableListOf<TopicPageSnapshot>()
  override suspend fun save(snapshot: TopicPageSnapshot) {
    saved.add(snapshot)
  }
}

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

fun CoroutineScope.testDispatcher(): CoroutineDispatcher =
  coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Unconfined
