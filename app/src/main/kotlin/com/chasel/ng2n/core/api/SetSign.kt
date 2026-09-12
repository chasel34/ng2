package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.bbcode.escapeForSubmit
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf

/**
 * 修改签名(API 文档 §11.3)。直译 `src/core/api/set-sign.ts`。
 *
 * ```
 * POST nuke.php?__lib=set_sign&__act=set&raw=3
 * form: uid=<自己 uid>, sign=<转义后的签名>
 * ```
 *
 * **只能改自己的**:服务端认 cookie 里的账号,uid 传别人的照样改不动,
 * 所以入口该由 UI 挡住(资料页只对当前账号显示编辑)。
 *
 * 签名要过 `escapeForSubmit`(API 文档 §13 第 4 条):emoji / ZWJ / 变体选择符
 * 不转成 UTF-16 十进制实体的话,服务端会拒收或存成乱码。读回来时
 * `parseUserProfile` 那条路上的两轮解码会还原,所以一转一读回得到原文。
 */
suspend fun updateSignature(
  client: NgaClient,
  /** 当前账号的 uid */
  uid: String,
  /** 原文(未转义),空串 = 清空签名 */
  signature: String,
) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      // 写操作:禁入格式轮换与换账号,失败不重放(修 P1-01)
      operation = Operation.WRITE,
      query = queryOf("__lib" to "set_sign", "__act" to "set", "raw" to 3),
      // 空值参数会被 buildQueryString 丢掉(API 文档 §0.4),清空签名得显式传一个空格,
      // 否则 `sign` 整个不传 = 服务端不知道要改成什么
      form = queryOf(
        "uid" to uid,
        "sign" to if (signature.isEmpty()) " " else escapeForSubmit(signature),
      ),
    ),
  )
}
