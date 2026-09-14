package com.chasel.ng2n.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersonaTendency(val title: String, val body: String, val evidence: List<String>,
  val counter: List<String> = emptyList(), val mentions: Int = 0, val bodyTail: String = "") {
  val hits: Int get() = if (mentions > 0) mentions else evidence.size
}
@Serializable
data class PersonaChange(val period: String, val body: String, val sources: List<String>)
@Serializable
data class PersonaReport(val overview: String, val processedSourceIds: List<String>,
  val interests: List<PersonaTendency>, val positions: List<PersonaTendency>, val judgment: String,
  val timeline: List<PersonaChange>, val boundary: String)

private val personaJson = Json { ignoreUnknownKeys = true }

// 初始样本的来源编号固定为 s1..sN；技能要求用 read_floor 补读只有标题的主楼，补读来源的编号在其后。
fun personaSampleId(id: String, sampleCount: Int): Boolean =
  (id.removePrefix("s").toIntOrNull() ?: 0) in 1..sampleCount

fun PersonaReport.processedSampleCount(sampleCount: Int): Int =
  processedSourceIds.count { personaSampleId(it, sampleCount) }

// 数量胶囊夹在 body 与 bodyTail 之间渲染，三段按此顺序读成一句话，胶囊只带数字与单位。
fun PersonaTendency.countPill(): String = "$hits 条"
fun PersonaTendency.sentence(): String = body + countPill() + bodyTail

// 本轮没有可展示的文字时不存在草稿，只能说明样本处理进度；状态卡另行说明文字与资料的去向。
fun personaPendingNote(sampleCount: Int, draftShown: Boolean, sourceCount: Int): String {
  val progress = "已读取 $sampleCount 条；本轮尚无已确认处理完成的样本，$sampleCount 条待确认处理。"
  return when {
    draftShown -> progress + "已有草稿保留，可继续。"
    sourceCount > 0 -> progress + "已登记 $sourceCount 个来源。"
    else -> progress
  }
}

// NGA 主楼的 pid 为 0；补读主楼与只有标题的初始样本同属主题首楼，不能标成回复。
fun personaEvidenceKind(source: AiSource): String = when {
  source.part == "summary" -> "主题标题"
  source.pid == 0L || source.floor == 0L -> "主楼"
  else -> "回复"
}

fun parsePersonaReport(text: String, sources: List<AiSource>, sampleCount: Int = sources.size): PersonaReport? = runCatching {
  val body = text.trim().removePrefix("```persona").removePrefix("```json").removeSuffix("```").trim()
  val report = personaJson.decodeFromString<PersonaReport>(body)
  val known = sources.map { it.id }.toSet()
  require(report.processedSourceIds.distinct().size == report.processedSourceIds.size)
  require(report.processedSourceIds.all { it in known })
  val processed = report.processedSourceIds.toSet()
  // 引用可以指向本会话内任意已注册来源；属于初始样本的编号仍必须先被确认处理，补读来源只需真实存在。
  fun citable(id: String) = id in known && (!personaSampleId(id, sampleCount) || id in processed)
  val samples = report.processedSampleCount(sampleCount)
  require((report.interests + report.positions).all { card -> card.evidence.isNotEmpty() &&
    (card.evidence + card.counter).all { citable(it) } && card.evidence.distinct().size == card.evidence.size &&
    card.counter.distinct().size == card.counter.size && card.evidence.intersect(card.counter.toSet()).isEmpty() })
  require(report.timeline.all { it.sources.isNotEmpty() && it.sources.all { id -> citable(id) } })
  report.copy(interests = report.interests.map { it.withCheckedMentions(samples) },
    positions = report.positions.map { it.withCheckedMentions(samples) })
}.getOrNull()

// 条数由模型自行清点，越界时回退到代表性发言数，不作废整份已合规的报告。
private fun PersonaTendency.withCheckedMentions(processed: Int): PersonaTendency =
  if (mentions in evidence.size..processed.coerceAtLeast(evidence.size)) this else copy(mentions = 0)

const val PERSONA_BOUNDARY = "只整理本人明确表达过的内容；不推断政治、健康、性向、宗教、民族等未公开身份或归属。结论限于已处理样本，不代表完整历史。引用他人、反讽与不同讨论条件须分别核对；证据不足时不判断。"
