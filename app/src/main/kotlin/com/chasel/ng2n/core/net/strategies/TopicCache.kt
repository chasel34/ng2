package com.chasel.ng2n.core.net.strategies

import com.chasel.ng2n.core.net.FetchContext
import com.chasel.ng2n.core.net.FetchStrategy
import com.chasel.ng2n.core.net.NgaEnvelope
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.NgaResult
import com.chasel.ng2n.core.net.QueryValue
import com.chasel.ng2n.core.net.StrategyOutcome
import com.chasel.ng2n.core.net.parseNgaJson
import kotlinx.coroutines.CancellationException

const val TOPIC_CACHE_STRATEGY_NAME = "topic-cache"

private const val SUPPORTED_PATH = "read.php"

data class TopicCacheKey(val tid: Long, val page: Int)

fun serializeEnvelope(envelope: NgaEnvelope): String = envelope.root.toString()

fun interface TopicCacheReader {
  suspend fun read(key: TopicCacheKey): String?
}

fun topicCacheKeyOf(request: NgaRequest): TopicCacheKey? {
  if (!request.allowTopicCache || !request.path.startsWith(SUPPORTED_PATH)) return null
  val query = request.query
  if (query["pid"] != null) return null
  if (query["authorid"] != null) return null

  val tid = numberOf(query["tid"]) ?: return null
  if (tid <= 0) return null
  val page = numberOf(query["page"]) ?: 1
  return TopicCacheKey(tid = tid, page = if (page > 0) page.toInt() else 1)
}

private fun numberOf(value: QueryValue?): Long? = when (value) {
  null -> null
  is QueryValue.Num -> value.value
  is QueryValue.Flag -> null
  is QueryValue.Text -> value.value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()
  is QueryValue.Gbk -> value.value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()
}

class TopicCacheStrategy(private val store: TopicCacheReader) : FetchStrategy {

  override val name: String = TOPIC_CACHE_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    val key = topicCacheKeyOf(request)
      ?: return unavailable("帖子缓存只认整帖阅读的 $SUPPORTED_PATH,这条是 ${request.path}")

    val payload = try {
      store.read(key)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (cause: Exception) {
      return unavailable("读缓存失败:${cause.message ?: cause::class.simpleName}")
    } ?: return unavailable("缓存里没有 tid=${key.tid} 的第 ${key.page} 页")

    return try {
      StrategyOutcome.Ok(
        NgaResult(parseNgaJson(payload, TOPIC_CACHE_STRATEGY_NAME), TOPIC_CACHE_STRATEGY_NAME),
      )
    } catch (cause: NgaError) {
      unavailable("缓存内容无法还原:${cause.text}")
    }
  }

  private fun unavailable(message: String): StrategyOutcome = StrategyOutcome.Failed(
    NgaError(NgaErrorKind.UNAVAILABLE, message, via = TOPIC_CACHE_STRATEGY_NAME),
  )
}
