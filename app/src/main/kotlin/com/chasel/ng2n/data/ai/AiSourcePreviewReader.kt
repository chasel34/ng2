package com.chasel.ng2n.data.ai

import com.chasel.ng2n.core.ai.buildTopicContext
import com.chasel.ng2n.core.ai.noteSourcePart
import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.data.topic.TopicPageParams
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive

// 只接收坐标与来源定位信息；对话和模型工作正文不能充当原文预览。
data class AiSourceCoordinate(val tid: Long, val pid: Long, val page: Int, val part: String? = null, val floor: Long = 0)
data class AiSourcePreview(val status: String, val title: String = "", val author: String = "",
  val floor: Long = 0, val page: Int = 1, val text: String = "", val content: String = "",
  val attachBase: String = "", val images: List<String> = emptyList(), val isNote: Boolean = false,
  val notes: List<AiSourcePreview> = emptyList(), val bodyFiltered: Boolean = false)

class AiSourcePreviewReader(
  private val readFresh: suspend (TopicPageParams) -> TopicDetail,
  private val rules: suspend () -> List<FilterRule>,
  private val urls: AttachmentUrls = DefaultAttachmentUrls,
) {
  suspend fun read(coordinate: AiSourceCoordinate): AiSourcePreview = try {
    val detail = readFresh(TopicPageParams(coordinate.tid, if (coordinate.pid > 0) 1 else coordinate.page,
      pid = coordinate.pid.takeIf { it > 0 }))
    check(detail.source != TopicSource.CACHE)
    val floor = (detail.floors + detail.hotReplies).firstOrNull { it.pid == coordinate.pid }
    if (floor == null) AiSourcePreview("unavailable") else {
      val currentRules = rules()
      // 按 pid 取单楼时 NGA 不返回楼层序号，页码也固定为请求页；坐标沿用会话保存的值。
      val byPid = coordinate.pid > 0
      val lou = floor.lou.takeIf { it > 0 } ?: if (byPid) coordinate.floor else 0
      val page = coordinate.page.takeIf { byPid && it > 0 } ?: detail.page
      fun preview(body: Floor, isNote: Boolean): AiSourcePreview {
        val selected = detail.copy(floors = listOf(body.copy(notes = emptyList())), hotReplies = emptyList())
        val source = buildTopicContext(selected, selected, rules = currentRules, urls = urls).sources.firstOrNull()
        return if (source == null) AiSourcePreview("filtered", floor = lou, page = page, isNote = isNote, bodyFiltered = true) else AiSourcePreview("ok", detail.subject, source.author,
          lou, page, source.text, body.content, detail.attachBase, source.images, isNote)
      }
      when (coordinate.part) {
        "floor", "summary" -> preview(floor, false).copy(notes = floor.notes.map { preview(it, true) })
        null -> preview(floor, false).copy(status = "range", notes = floor.notes.map { preview(it, true) })
        else -> floor.notes.filter { noteSourcePart(it) == coordinate.part }.singleOrNull()
          ?.let { preview(it, true) } ?: AiSourcePreview("unavailable")
      }
    }
  } catch (e: CancellationException) { throw e }
  catch (e: Exception) { AiSourcePreview(ForumToolSession.classify(e).getValue("status").jsonPrimitive.content) }
}
