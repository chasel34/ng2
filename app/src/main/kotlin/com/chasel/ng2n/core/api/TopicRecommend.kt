package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonObject

enum class RecommendAction { LIKE, DISLIKE }

enum class RecommendState { NONE, LIKED, DISLIKED }

data class RecommendMark(
  val state: RecommendState = RecommendState.NONE,
  val scoreDelta: Long = 0,
)

data class RecommendResult(val state: RecommendState, val delta: Long)

private fun scoreOf(state: RecommendState): Long = when (state) {
  RecommendState.LIKED -> 1
  RecommendState.DISLIKED -> -1
  RecommendState.NONE -> 0
}

fun nextRecommendState(current: RecommendState, action: RecommendAction): RecommendState =
  if (action == RecommendAction.LIKE) {
    if (current == RecommendState.LIKED) RecommendState.NONE else RecommendState.LIKED
  } else {
    if (current == RecommendState.DISLIKED) RecommendState.NONE else RecommendState.DISLIKED
  }

fun expectedRecommendDelta(current: RecommendState, action: RecommendAction): Long =
  scoreOf(nextRecommendState(current, action)) - scoreOf(current)

fun recommendStateOf(action: RecommendAction, delta: Long): RecommendState = when {
  action == RecommendAction.LIKE && delta > 0 -> RecommendState.LIKED
  action == RecommendAction.DISLIKE && delta < 0 -> RecommendState.DISLIKED
  else -> RecommendState.NONE
}

suspend fun postRecommend(
  client: NgaClient,
  tid: Long,
  pid: Long,
  action: RecommendAction,
): RecommendResult {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
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
  val delta = int(data, "1") ?: int(data, "0")
    ?: throw NgaError(NgaErrorKind.PARSE, "赞踩响应里没有分数增量", via = result.via)
  return RecommendResult(delta = delta, state = recommendStateOf(action, delta))
}
