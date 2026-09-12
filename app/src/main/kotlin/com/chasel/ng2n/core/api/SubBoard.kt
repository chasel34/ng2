package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.QueryValue
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

val SUBSCRIBED_ATTRIBUTES: List<Long> = listOf(7, 558, 542, 2606, 2590, 4654)

const val FILTERABLE_ATTRIBUTES_MIN = 40L

enum class SubBoardSubscription { SUBSCRIBED, BLOCKED, UNKNOWN }

@Serializable
data class SubBoardState(
  val subscribed: Boolean,
  val filterable: Boolean,
  @Transient val known: Boolean = false,
) {
  val subscription: SubBoardSubscription
    get() = when {
      subscribed -> SubBoardSubscription.SUBSCRIBED
      known -> SubBoardSubscription.BLOCKED
      else -> SubBoardSubscription.UNKNOWN
    }
}

fun subBoardState(attributes: Long): SubBoardState {
  val subscribed = attributes in SUBSCRIBED_ATTRIBUTES
  return SubBoardState(
    subscribed = subscribed,
    filterable = attributes > FILTERABLE_ATTRIBUTES_MIN,
    known = subscribed,
  )
}

enum class SubBoardAction { SUBSCRIBE, BLOCK }

fun subBoardOptionParam(action: SubBoardAction, filterType: Int): String {
  val subscribeParam = if (filterType == 1) "del" else "add"
  return if (action == SubBoardAction.SUBSCRIBE) {
    subscribeParam
  } else {
    if (subscribeParam == "del") "add" else "del"
  }
}

fun nextSubBoardState(state: SubBoardState, action: SubBoardAction): SubBoardState =
  state.copy(subscribed = action == SubBoardAction.SUBSCRIBE, known = true)

suspend fun setSubBoardOption(
  client: NgaClient,
  subBoard: SubBoard,
  parentFid: Long,
  action: SubBoardAction,
) {
  val param = subBoardOptionParam(action, subBoard.filterType)

  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = linkedMapOf<String, QueryValue?>(
        "__lib" to QueryValue.Text("user_option"),
        "__act" to QueryValue.Text("set"),
        "raw" to QueryValue.Num(3),
        param to QueryValue.Num(subBoard.filterId),
      ),
      form = queryOf(
        "fid" to parentFid,
        "type" to subBoard.filterType,
        "info" to "add_to_block_tids",
      ),
    ),
  )
}
