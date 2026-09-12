package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.SubBoard
import com.chasel.ng2n.core.api.SubBoardAction
import com.chasel.ng2n.core.api.SubBoardState
import com.chasel.ng2n.core.api.nextSubBoardState
import com.chasel.ng2n.core.api.setSubBoardOption
import com.chasel.ng2n.core.api.subBoardState
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.session.SubBoardOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubBoardRepository @Inject constructor(
  private val client: NgaClient,
  private val overrides: SubBoardOverrides,
) {

  fun keyOf(uid: String, subBoard: SubBoard): String =
    overrides.keyOf(uid, subBoard.filterId.toString())

  fun stateOf(uid: String?, subBoard: SubBoard, overrideMap: Map<String, Boolean>): SubBoardState {
    val parsed = subBoardState(subBoard.attributes)
    if (uid == null) return parsed
    val override = overrideMap[keyOf(uid, subBoard)] ?: return parsed
    return nextSubBoardState(parsed, if (override) SubBoardAction.SUBSCRIBE else SubBoardAction.BLOCK)
  }

  suspend fun toggle(uid: String, subBoard: SubBoard, parentFid: Long, action: SubBoardAction) {
    val key = keyOf(uid, subBoard)
    if (key in overrides.inFlight.value) return
    val previous = overrides.beginToggle(key, action == SubBoardAction.SUBSCRIBE)
    withContext(Dispatchers.IO) {
      try {
        setSubBoardOption(client, subBoard = subBoard, parentFid = parentFid, action = action)
      } catch (error: Throwable) {
        overrides.rollback(key, previous)
        throw error
      } finally {
        overrides.endToggle(key)
      }
    }
  }

  val overrideFlow get() = overrides.overrides
  val inFlightFlow get() = overrides.inFlight
}
