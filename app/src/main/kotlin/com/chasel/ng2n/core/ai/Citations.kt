package com.chasel.ng2n.core.ai

sealed interface AnswerPart {
  data class Text(val value: String) : AnswerPart
  data class Source(val id: String) : AnswerPart
}

fun parseCitations(text: String, knownIds: Set<String>): List<AnswerPart> {
  val result = mutableListOf<AnswerPart>()
  val pending = StringBuilder()
  fun flush() { if (pending.isNotEmpty()) { result += AnswerPart.Text(pending.toString()); pending.clear() } }
  var i = 0
  while (i < text.length) {
    if (text.startsWith("[[", i)) {
      flush()
      val end = text.indexOf("]]", i + 2)
      if (end < 0) break
      val id = text.substring(i + 2, end)
      if (id.matches(Regex("s[1-9][0-9]*")) && id in knownIds) result += AnswerPart.Source(id)
      i = end + 2
    } else {
      if (i == text.lastIndex && text[i] == '[') break
      pending.append(text[i++])
    }
  }
  flush()
  return result
}

enum class AiSheetPosition { HALF, FULL, COLLAPSED }
fun snapAiSheet(topFraction: Float): AiSheetPosition = when {
  topFraction < 170f / 844 -> AiSheetPosition.FULL
  topFraction > 560f / 844 -> AiSheetPosition.COLLAPSED
  else -> AiSheetPosition.HALF
}

val AI_CLAIM_STATUSES = listOf("支持", "部分支持", "无法核实")

data class AiClaim(val index: Int, val status: String, val claim: String, val note: String)
sealed interface AnswerBlock {
  data class Markdown(val text: String) : AnswerBlock
  data class Claim(val value: AiClaim) : AnswerBlock
}

private val CLAIM_OPEN = Regex("^:{3}claim +(\\S+) *$")

fun splitAnswerBlocks(text: String): List<AnswerBlock> {
  val blocks = mutableListOf<AnswerBlock>()
  val plain = mutableListOf<String>()
  var claim: Pair<String, MutableList<String>>? = null
  var fenced = false
  var index = 0
  fun flush() { if (plain.isNotEmpty()) { blocks += AnswerBlock.Markdown(plain.joinToString("\n")); plain.clear() } }
  fun close(state: Pair<String, MutableList<String>>) {
    val lines = state.second.dropWhile { it.isBlank() }
    blocks += AnswerBlock.Claim(AiClaim(++index, state.first, lines.firstOrNull().orEmpty(),
      lines.drop(1).joinToString("\n").trim()))
  }
  text.lines().forEach { line ->
    if (claim != null) {
      if (line.trim() == ":::") { close(claim); claim = null } else claim.second += line
      return@forEach
    }
    if (line.trimStart().startsWith("```")) fenced = !fenced
    val open = if (fenced) null else CLAIM_OPEN.find(line.trim())
    if (open != null && open.groupValues[1] in AI_CLAIM_STATUSES) { flush(); claim = open.groupValues[1] to mutableListOf() }
    else plain += line
  }
  claim?.let { close(it) }
  flush()
  return blocks
}

// 只有标记、没有正文的末尾行是停止或中断留下的残缺输出，按字面渲染像是渲染出错；未闭合的围栏内一律保持原样。
private val BARE_MARKUP = Regex("^(#{1,6}|[-*+]|\\d+[.)]|>|\\|+|`{1,2}|\\*{1,3}|_{1,3})$")

fun trimIncompleteMarkdown(text: String): String {
  val lines = text.lines()
  if (lines.count { it.trimStart().startsWith("```") } % 2 != 0) return text
  var end = lines.size
  while (end > 0 && lines[end - 1].trim().let { it.isEmpty() || BARE_MARKUP.matches(it) }) end--
  return if (end == lines.size) text else lines.take(end).joinToString("\n")
}
