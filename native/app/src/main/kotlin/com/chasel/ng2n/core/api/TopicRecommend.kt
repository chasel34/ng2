package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonObject

/**
 * 点赞 / 点踩(API 文档 §6,`nuke.php?__lib=topic_recommend`)。
 * 直译 `src/core/api/topic-recommend.ts`。
 *
 * NGA 的赞踩是**切换式**:同一动作按第二次是取消,先踩再赞会把踩直接翻成赞。
 * 服务端不回「现在是什么状态」,只回分数增量 delta——最终状态按「动作 + delta 符号」
 * 判([recommendStateOf])。客户端乐观更新用的预测迁移([nextRecommendState] /
 * [expectedRecommendDelta])与这套 delta 语义保持一致,单测互相锁死。
 */

enum class RecommendAction { LIKE, DISLIKE }

/** 当前用户对某楼层的赞踩状态。服务端不下发初值,会话内从 [NONE] 起算。 */
enum class RecommendState { NONE, LIKED, DISLIKED }

/**
 * 一个楼层的本地赞踩标记:状态 + 相对服务端 `score` 的累计增量。
 * UI 显示的赞数 = `floor.score + scoreDelta`。
 */
data class RecommendMark(
  val state: RecommendState = RecommendState.NONE,
  val scoreDelta: Long = 0,
)

/** 一次赞踩请求的结果:服务端 delta 与据它判出的最终状态。 */
data class RecommendResult(val state: RecommendState, val delta: Long)

/** 状态对分数的贡献:已赞 +1、已踩 -1、没表态 0。迁移 delta 都由它导出。 */
private fun scoreOf(state: RecommendState): Long = when (state) {
  RecommendState.LIKED -> 1
  RecommendState.DISLIKED -> -1
  RecommendState.NONE -> 0
}

/** 切换式状态迁移:同一动作再按一次是取消,反向动作直接翻面。 */
fun nextRecommendState(current: RecommendState, action: RecommendAction): RecommendState =
  if (action == RecommendAction.LIKE) {
    if (current == RecommendState.LIKED) RecommendState.NONE else RecommendState.LIKED
  } else {
    if (current == RecommendState.DISLIKED) RecommendState.NONE else RecommendState.DISLIKED
  }

/**
 * 预测这次动作会让分数变多少(乐观更新用)。
 * 从踩翻成赞是 +2(撤一踩再加一赞),与服务端实际返回的 delta 一致。
 */
fun expectedRecommendDelta(current: RecommendState, action: RecommendAction): Long =
  scoreOf(nextRecommendState(current, action)) - scoreOf(current)

/**
 * 按服务端 delta 判最终状态(API 文档 §6):
 * 点赞且 delta>0 → 已赞;点踩且 delta<0 → 已踩;其余都是取消/无表态。
 */
fun recommendStateOf(action: RecommendAction, delta: Long): RecommendState = when {
  action == RecommendAction.LIKE && delta > 0 -> RecommendState.LIKED
  action == RecommendAction.DISLIKE && delta < 0 -> RecommendState.DISLIKED
  else -> RecommendState.NONE
}

/**
 * 发一次赞/踩。语义错误(未登录、操作太快)由 envelope 抛 `kind = SERVER`,
 * 能返回就是服务端已经记上了。
 *
 * @param pid 楼层 pid;**主楼传 0**(API 文档 §6),0 也必须真的出现在参数里
 */
suspend fun postRecommend(
  client: NgaClient,
  tid: Long,
  pid: Long,
  action: RecommendAction,
): RecommendResult {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      // 写操作:禁入格式轮换与换账号,失败不重放(修 P1-01)——
      // 赞踩是切换式的,重放一次就把刚点的赞取消掉了
      operation = Operation.WRITE,
      query = queryOf(
        "__lib" to "topic_recommend",
        "__act" to "add",
        "value" to if (action == RecommendAction.LIKE) 1 else -1,
        "tid" to tid,
        "pid" to pid,
      ),
    ),
  )

  val data = result.data as? JsonObject ?: JsonObject(emptyMap())
  // delta 在 data["1"] 或 data["0"](API 文档 §6);data["0"] 偶尔是文案,解不出数就看下一个
  val delta = int(data, "1") ?: int(data, "0")
    ?: throw NgaError(NgaErrorKind.PARSE, "赞踩响应里没有分数增量", via = result.via)
  return RecommendResult(delta = delta, state = recommendStateOf(action, delta))
}
