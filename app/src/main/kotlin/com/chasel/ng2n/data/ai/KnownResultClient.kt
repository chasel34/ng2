package com.chasel.ng2n.data.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.IncompleteStreamException
import kotlinx.coroutines.flow.catch

internal class KnownResultClient(private val delegate: LLMClient) : LLMClient() {
  override fun getStandardJsonSchemaGenerator() = delegate.getStandardJsonSchemaGenerator()
  override fun getBasicJsonSchemaGenerator() = delegate.getBasicJsonSchemaGenerator()
  override fun llmProvider() = delegate.llmProvider()
  override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = delegate.execute(prompt, model, tools)
  override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
    delegate.executeStreaming(prompt, model, tools).catch { error ->
      // RetryingLLMClient 无条件重试该异常；空流也可能是已发送但结果不明。
      if (error is IncompleteStreamException) throw IllegalStateException("模型请求结果不明")
      throw error
    }
  override suspend fun moderate(prompt: Prompt, model: LLModel) = delegate.moderate(prompt, model)
  override fun close() = delegate.close()
}
