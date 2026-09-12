package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.bbcode.escapeForSubmit
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf

suspend fun updateSignature(
  client: NgaClient,
  uid: String,
  signature: String,
) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to "set_sign", "__act" to "set", "raw" to 3),
      form = queryOf(
        "uid" to uid,
        "sign" to if (signature.isEmpty()) " " else escapeForSubmit(signature),
      ),
    ),
  )
}
