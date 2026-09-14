package com.chasel.ng2n.data.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.chatAgentStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import javax.inject.Inject

class KoogAgentFactory @Inject constructor() {
  suspend fun run(
    input: String,
    executor: PromptExecutor,
    config: AIAgentConfig,
    tools: ToolRegistry,
    installFeatures: GraphAIAgent.FeatureContext.() -> Unit = {},
  ): String {
    val agent = create(executor, config, tools, installFeatures)
    return try {
      agent.run(input)
    } finally {
      agent.close()
    }
  }

  fun create(
    executor: PromptExecutor,
    config: AIAgentConfig,
    tools: ToolRegistry,
    installFeatures: GraphAIAgent.FeatureContext.() -> Unit = {},
  ): GraphAIAgent<String, String> = AIAgent(
    promptExecutor = executor,
    agentConfig = config,
    strategy = chatAgentStrategy(),
    toolRegistry = tools,
    installFeatures = installFeatures,
  )
}
