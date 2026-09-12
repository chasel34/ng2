package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonObject

private const val ALREADY_MESSAGE = "今天已经签到"

data class CheckInResult(
  val alreadyCheckedIn: Boolean,
  val message: String? = null,
)

suspend fun checkIn(client: NgaClient): CheckInResult {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to "check_in", "__act" to "check_in"),
    ),
  )

  val fake = result.fakeError?.message
  val ok = (result.data as? JsonObject)?.let { str(it, "0") }
  val message = fake ?: ok

  return CheckInResult(
    alreadyCheckedIn = fake != null && fake.contains(ALREADY_MESSAGE),
    message = message,
  )
}
