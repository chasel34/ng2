package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonObject

/**
 * 每日签到(CONTEXT.md「签到」,API 文档 §11.4)。直译 `src/core/api/check-in.ts`。
 *
 * ```
 * POST nuke.php?__lib=check_in&__act=check_in     # 无额外参数
 * ```
 *
 * 「今天已经签到」在**假错误白名单**里(core/net 的 `FAKE_ERROR_MESSAGES`),
 * 命中时 `parseNgaJson` 已经当成功返回了,这里只是把它认出来,好让 UI
 * 说「今天已经签过」而不是「签到成功」——两句话对用户不是一回事。
 *
 * 去重在 `data/settings/CheckInDays.kt`(票 14 已落地,按 UTC+8 日期本地记账):
 * 今天签过就压根不发这个请求。
 */

/** 服务端说「今天已经签到」时的判据。 */
private const val ALREADY_MESSAGE = "今天已经签到"

data class CheckInResult(
  /** 服务端说今天已经签过了(不是失败,只是这次没算新的一签) */
  val alreadyCheckedIn: Boolean,
  /** 服务端原话,拿去 toast;拿不到就由 UI 用默认文案 */
  val message: String? = null,
)

/**
 * 签一次到。真失败(未登录、被封)由 envelope 抛 `kind = SERVER`,能返回就是成功。
 */
suspend fun checkIn(client: NgaClient): CheckInResult {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      // 写操作:禁入格式轮换与换账号,失败不重放(修 P1-01)
      operation = Operation.WRITE,
      query = queryOf("__lib" to "check_in", "__act" to "check_in"),
    ),
  )

  val fake = result.fakeError?.message
  // 成功时的文案在 data["0"](和别的写操作一样是一句「操作成功」类的话)
  val ok = (result.data as? JsonObject)?.let { str(it, "0") }
  val message = fake ?: ok

  return CheckInResult(
    alreadyCheckedIn = fake != null && fake.contains(ALREADY_MESSAGE),
    message = message,
  )
}
