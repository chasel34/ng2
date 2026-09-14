package com.chasel.ng2n.data.ai

import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

class DeepSeekClientFactory @Inject constructor() {
  companion object {
    val retryConfig = ai.koog.prompt.executor.clients.retry.RetryConfig(maxAttempts = 2,
      retryablePatterns = listOf(ai.koog.prompt.executor.clients.retry.RetryablePattern.Regex(Regex("^AI_RETRY_HTTP (429|500|503) retryAfterMs=[0-9]+$"))),
      retryAfterExtractor = object : ai.koog.prompt.executor.clients.retry.RetryAfterExtractor {
        override fun extract(errorMessage: String): kotlin.time.Duration? =
          Regex("retryAfterMs=([0-9]+)").find(errorMessage)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.milliseconds
      })
  }
  fun createExecutor(apiKey: String, budget: AiRunBudget? = null): PromptExecutor = MultiLLMPromptExecutor(ai.koog.prompt.executor.clients.retry.RetryingLLMClient(KnownResultClient(create(apiKey, DeepSeekClientSettings(), budget)), retryConfig))

  fun create(apiKey: String): DeepSeekLLMClient = create(apiKey, DeepSeekClientSettings())

  internal fun create(apiKey: String, settings: DeepSeekClientSettings, budget: AiRunBudget? = null): DeepSeekLLMClient {
    require(apiKey.isNotBlank())
    return DeepSeekLLMClient(
      apiKey = apiKey,
      settings = settings,
      // 框架 factory 每次新建 OkHttpClient，不继承论坛的 CookieJar、拦截器或连接池。
      httpClientFactory = BudgetHttpFactory(budget),
    )
  }
}
