package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.CheckInResult
import com.chasel.ng2n.core.api.checkIn
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.settings.CheckInDays
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.settings.isCheckedInOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CheckInOutcome {
  data object AlreadyToday : CheckInOutcome

  data object InFlight : CheckInOutcome

  data class CheckedIn(val result: CheckInResult) : CheckInOutcome
}

enum class CheckInDecision { SEND, ALREADY_TODAY, IN_FLIGHT }

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
    return withContext(Dispatchers.IO) {
      try {
        val result = checkIn(client)
        settings.markCheckedIn(uid, System.currentTimeMillis())
        CheckInOutcome.CheckedIn(result)
      } finally {
        pending.value = null
      }
    }
  }
}
