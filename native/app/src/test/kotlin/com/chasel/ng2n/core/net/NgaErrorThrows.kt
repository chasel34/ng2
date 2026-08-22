package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.DefaultGoldenThrowDescriber
import com.chasel.ng2n.golden.GoldenThrowDescriber
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 金样本里 `expected.throws` 的描述器(05b 在 `GoldenAssert.kt` 里留的口子)。
 *
 * 字段与导出器 `scripts/export-goldens.mts:describeThrow` 一一对应:
 * `kind` / `message` / `retryable` 必给,`code` / `status` / `via` 只在有值时出现。
 * **`retryable` 决定反封锁链走不走下去,是语义的一部分,漏掉等于没对拍**(ADR-0002)。
 */
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
