package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.USER_AGENT_PROFILES
import com.chasel.ng2n.core.net.UserAgentProfile
import java.util.concurrent.atomic.AtomicBoolean

class SystemUserAgent(
  private val onMainThread: () -> Boolean,
  private val readSystemUserAgent: () -> String,
  private val postToMainThread: (() -> Unit) -> Unit,
  private val fallback: String = USER_AGENT_PROFILES.getValue(UserAgentProfile.WEBVIEW),
) {

  @Volatile
  private var cached: String? = null

  private val posted = AtomicBoolean(false)

  fun prewarm() {
    if (cached != null) return
    if (onMainThread()) evaluate() else scheduleOnMainThread()
  }

  fun get(): String {
    cached?.let { return it }
    if (onMainThread()) return evaluate() ?: fallback
    scheduleOnMainThread()
    return fallback
  }

  private fun scheduleOnMainThread() {
    if (!posted.compareAndSet(false, true)) return
    postToMainThread {
      try {
        evaluate()
      } finally {
        posted.set(false)
      }
    }
  }

  private fun evaluate(): String? {
    val value = runCatching { readSystemUserAgent() }.getOrNull()?.takeIf { it.isNotBlank() }
    if (value != null) cached = value
    return value
  }
}
