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

/** 只有 `read.php` 有缓存(缓存的粒度就是「主题的一页」)。 */
private const val SUPPORTED_PATH = "read.php"

/** 缓存一页的定位:主题 + 页码(从 1 起)。 */
data class TopicCacheKey(val tid: Long, val page: Int)

/**
 * 把一次响应存成可以还原的文本(票 07 补上写侧的这一半)。
 *
 * 存顶层 `root` 而不是 `data`:`data` 是 `parseNgaJson` 按「有没有 data/error 键」
 * 推出来的,只存它的话还原时推不回同一个结果(不套壳的接口会被当成套壳的)。
 * Web 反解档(票 08)产出的信封 root 同样是普通对象,两条路存出来的东西可以互换。
 */
fun serializeEnvelope(envelope: NgaEnvelope): String = envelope.root.toString()

/**
 * 缓存档要的最小存储口。设备侧接 Room(`data/cache/TopicCacheRepository`,票 14),
 * 单测接一个 Map —— core 层零 Android 依赖,存储一律注入。
 *
 * 返回的是**序列化后的信封**(顶层 `root` 的 JSON 文本),不是解析结果:
 * 还原走 `parseNgaJson`,与在线那条路完全同一段代码,信封天然同构。
 */
fun interface TopicCacheReader {
  suspend fun read(key: TopicCacheKey): String?
}

/**
 * 这条请求缓存得起来吗?缓存得起来就给出它的 key。
 *
 * 只认整帖阅读:带 `pid`(只看该楼)或 `authorid`(只看某人)的请求是**过滤视图**,
 * 服务端会重排楼层与页码,按 tid+page 存下来会污染整帖那一份。
 * fav 码不影响内容(它是访问凭据,不是筛选条件),所以带不带 fav 命中同一份缓存。
 */
fun topicCacheKeyOf(request: NgaRequest): TopicCacheKey? {
  if (!request.path.startsWith(SUPPORTED_PATH)) return null
  val query = request.query
  if (query["pid"] != null) return null
  if (query["authorid"] != null) return null

  val tid = numberOf(query["tid"]) ?: return null
  if (tid <= 0) return null
  val page = numberOf(query["page"]) ?: 1
  return TopicCacheKey(tid = tid, page = if (page > 0) page.toInt() else 1)
}

/** TS 的 `numberOf`:数字直接用,字符串按 `Number()` 转,转不出来当没有。 */
private fun numberOf(value: QueryValue?): Long? = when (value) {
  null -> null
  is QueryValue.Num -> value.value
  is QueryValue.Flag -> null
  is QueryValue.Text -> value.value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()
  is QueryValue.Gbk -> value.value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()
}

/**
 * 反封锁链的帖子缓存档(ADR-0002:链的最后一档)。
 * 直译 `src/core/net/strategies/topic-cache.ts`。
 *
 * 前面几档都在打网络,这一档一个请求都不发——断网、被封、账号全挂的时候,
 * 本机存着的那一页就是用户还能看到的东西。缓存里没有就报 `UNAVAILABLE` 让链继续
 * (`runStrategyChain` 不会拿它盖掉前面更实质的错误)。
 */
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
      // 本地库出问题不该顶替「这一页被封了」当最终错误,仍按「这一档不适用」处理
      return unavailable("读缓存失败:${cause.message ?: cause::class.simpleName}")
    } ?: return unavailable("缓存里没有 tid=${key.tid} 的第 ${key.page} 页")

    return try {
      StrategyOutcome.Ok(
        NgaResult(parseNgaJson(payload, TOPIC_CACHE_STRATEGY_NAME), TOPIC_CACHE_STRATEGY_NAME),
      )
    } catch (cause: NgaError) {
      // 存进去的东西自己解不出来(旧版本写的、写坏了):当作没缓存
      unavailable("缓存内容无法还原:${cause.text}")
    }
  }

  private fun unavailable(message: String): StrategyOutcome = StrategyOutcome.Failed(
    NgaError(NgaErrorKind.UNAVAILABLE, message, via = TOPIC_CACHE_STRATEGY_NAME),
  )
}
