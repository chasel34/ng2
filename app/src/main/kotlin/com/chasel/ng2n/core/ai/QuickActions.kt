package com.chasel.ng2n.core.ai

data class QuickAction(val skillId: String, val label: String, val description: String, val task: String)

object BuiltinQuickActions {
  const val VERSION = "6"
  val all = listOf(
    QuickAction("discussion-overview", "讨论概览", "归纳议题、共识与分歧", "请讨论概览，说明实际阅读范围并附来源。"),
    QuickAction("fact-check", "事实核查", "提取可核查的说法，查找来源并说明证据状态", "请事实核查，说明实际阅读范围并附来源。"),
    QuickAction("critical-thinking", "批判性思考", "检查前提、证据、推理和遗漏条件", "请批判性思考，说明实际阅读范围并附来源。"),
    QuickAction("disagreements", "梳理分歧", "各方主张、共识，以及分歧的类型", "请梳理分歧，说明实际阅读范围并附来源。"),
    QuickAction("background", "补充背景", "联网查找讨论涉及的背景资料", "请补充背景，说明实际阅读范围并附来源。"),
    QuickAction("prior-context", "查找前情", "追踪引用与相关发言", "请查找前情，说明实际阅读范围并附来源。"),
    QuickAction("arguments", "检查各方论证", "比较主张与论据的关联", "请检查各方论证，说明实际阅读范围并附来源。"),
    QuickAction("persona-evidence", "查看倾向依据", "查找反复出现的表述依据", "请查看倾向依据，说明实际阅读范围并附来源。"),
    QuickAction("persona-counterexamples", "查找相反表述", "查找与倾向判断相反的证据", "请查找相反表述，说明实际阅读范围并附来源。"),
    QuickAction("persona-changes", "查看观点变化", "按时间比较明确表达的观点", "请查看观点变化，说明实际阅读范围并附来源。")
  )
  fun forEntry(floor: Boolean): List<QuickAction> = all.filter { it.skillId in
    if (floor) setOf("fact-check", "critical-thinking", "prior-context")
    else setOf("fact-check", "critical-thinking", "disagreements", "background") }.map { action ->
      if (!floor) action else action.copy(description = when (action.skillId) {
        "fact-check" -> "核查这条发言里可验证的说法"
        "critical-thinking" -> "检查这条发言的前提与推理"
        else -> "读取回复链与同作者的前序发言"
      })
    }
  fun forEntry(kind: String, floor: Boolean = kind == "楼层"): List<QuickAction> = when (kind) {
    "个人" -> all.filter { it.skillId.startsWith("persona-") }
    "列表" -> listOf(
      all.first { it.skillId == "discussion-overview" }.copy(label = "深入某个话题", description = "选择列表中的主题并读取正文与楼层", task = "请选择列表中值得深入的主题，用论坛工具读取正文与楼层后分析，并说明范围和来源。"),
      all.first { it.skillId == "disagreements" }.copy(label = "梳理讨论分歧"),
      all.first { it.skillId == "background" })
    "回复链" -> listOf("disagreements", "arguments", "fact-check").map { id -> all.first { it.skillId == id } }
    else -> forEntry(floor)
  }
  fun query(text: String): String? = Regex("(^|\\s)/(\\S*)$").find(text)?.groupValues?.get(2)
  fun filter(actions: List<QuickAction>, query: String) = actions.filter { it.label.startsWith(query) }
}
