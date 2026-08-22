package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.CheckInResult
import com.chasel.ng2n.core.api.checkIn
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.settings.CheckInDays
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.settings.isCheckedInOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每日签到(CONTEXT.md「签到」)的设备侧落地 —— 直译 RN 侧 `store/check-in.ts`。
 *
 * **今天签过就不发请求**:服务端没有「查今天签没签」的接口,重复签到只会回一句
 * 假错误,白打一次接口(ADR-0002:能少打就少打)。在途的那次也挡住重复点击。
 * 按 UTC+8 记账的纯函数在票 14 的 `data/settings/CheckInDays.kt`。
 */
sealed interface CheckInOutcome {
  /** 本地记录显示今天已经签过,压根没发请求 */
  data object AlreadyToday : CheckInOutcome

  /** 上一次还在途,这次点击被忽略 */
  data object InFlight : CheckInOutcome

  /** 发了请求且成功;[result] 的 `alreadyCheckedIn` 表示服务端说今天已签过 */
  data class CheckedIn(val result: CheckInResult) : CheckInOutcome
}

/** 这一次点击到底该不该发请求。三档判定,顺序照抄 RN 版。 */
enum class CheckInDecision { SEND, ALREADY_TODAY, IN_FLIGHT }

/**
 * 纯判定:先看本地记账(今天签过就一律不发),再看有没有在途请求。
 *
 * **`pendingUid` 判的是「有没有任何一次在途」而不是「本账号在途」** —— RN 版原行为
 * (`store/check-in.ts` 的 `get().pendingUid !== null`)。切号后立刻再点会落到这一支,
 * 照抄不改:那一发的凭证是发起时定格的,并发两发签到没有意义。
 */
fun decideCheckIn(
  days: CheckInDays,
  uid: String,
  nowMs: Long,
  pendingUid: String?,
): CheckInDecision = when {
  isCheckedInOn(days, uid, nowMs) -> CheckInDecision.ALREADY_TODAY
  pendingUid != null -> CheckInDecision.IN_FLIGHT
  else -> CheckInDecision.SEND
}

@Singleton
class CheckInRepository @Inject constructor(
  private val client: NgaClient,
  private val settings: SettingsStore,
) {

  private val pending = MutableStateFlow<String?>(null)

  /** 正在签的账号 uid;null = 没有在途请求。抽屉那行的「签到中…」看它。 */
  val pendingUid: StateFlow<String?> = pending.asStateFlow()

  val days get() = settings.checkInDays

  suspend fun checkInNow(uid: String, nowMs: Long = System.currentTimeMillis()): CheckInOutcome {
    val decision = decideCheckIn(settings.currentCheckInDays(), uid, nowMs, pending.value)
    when (decision) {
      CheckInDecision.ALREADY_TODAY -> return CheckInOutcome.AlreadyToday
      CheckInDecision.IN_FLIGHT -> return CheckInOutcome.InFlight
      CheckInDecision.SEND -> Unit
    }

    pending.value = uid
    try {
      val result = checkIn(client)
      // 服务端说「今天已经签到」也记上:今天剩下的时间不必再问了
      settings.markCheckedIn(uid, System.currentTimeMillis())
      return CheckInOutcome.CheckedIn(result)
    } finally {
      pending.value = null
    }
  }
}
