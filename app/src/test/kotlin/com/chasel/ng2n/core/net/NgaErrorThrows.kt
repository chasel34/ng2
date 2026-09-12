package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.DefaultGoldenThrowDescriber
import com.chasel.ng2n.golden.GoldenThrowDescriber
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val NgaErrorThrowDescriber = GoldenThrowDescriber { error ->
  if (error !is NgaError) {
    DefaultGoldenThrowDescriber.describe(error)
  } else {
    buildJsonObject {
      put("kind", error.kind.wire)
      put("message", error.text)
      put("retryable", error.retryable)
      error.code?.let { put("code", it) }
      error.status?.let { put("status", it) }
      error.via?.let { put("via", it) }
    }
  }
}
