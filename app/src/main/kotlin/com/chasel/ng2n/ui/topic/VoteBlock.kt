package com.chasel.ng2n.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.local.Vote
import com.chasel.ng2n.core.local.VoteKind
import com.chasel.ng2n.core.local.VoteOption
import com.chasel.ng2n.core.local.isVoteClosed
import com.chasel.ng2n.core.local.voteSharePercent
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun VoteBlock(vote: Vote, onNotAvailable: () -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val closed = isVoteClosed(vote, System.currentTimeMillis() / 1000)
  val label = KIND_LABELS.getValue(vote.kind)

  Column(
    modifier = modifier
      .padding(top = 11.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.surface2)
      .padding(Spacing.md),
    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        fontSize = Typo.notice.size,
        fontWeight = FontWeight.SemiBold,
        color = colors.fg,
        modifier = Modifier.weight(1f),
      )
      if (closed) Text("已结束", fontSize = Typo.listMeta.size, color = colors.meta)
    }

    vote.groups.forEachIndexed { index, group ->
      Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        group.title?.let {
          Text(
            text = "$it · 共 ${group.votes} 票",
            fontSize = Typo.listMeta.size,
            color = colors.meta,
            modifier = Modifier.padding(top = Spacing.xs),
          )
        }
        group.options.forEach { option ->
          VoteRow(option = option, groupVotes = group.votes, multiple = vote.multiple)
        }
      }
      if (index < vote.groups.lastIndex) Box(Modifier.height(Spacing.xs))
    }

    Text(summaryOf(vote, label), fontSize = Typo.listMeta.size, color = colors.meta)

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(38.dp)
        .clip(RoundedCornerShape(Radius.md))
        .background(if (closed) colors.track else colors.primary)
        .clickable(onClick = onNotAvailable),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = if (closed) "${label}已结束" else label,
        fontSize = Typo.notice.size,
        fontWeight = FontWeight.SemiBold,
        color = colors.onPrimary,
      )
    }
  }
}

@Composable
private fun VoteRow(option: VoteOption, groupVotes: Long, multiple: Boolean) {
  val colors = LocalNg2nColors.current
  val percent = voteSharePercent(option.votes, groupVotes)
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ChoiceIcon(
        tint = if (option.chosen) colors.primary else colors.meta,
        multiple = multiple,
        chosen = option.chosen,
      )
      Text(
        text = option.title,
        fontSize = Typo.notice.size,
        color = colors.fg,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "${option.votes} 票 · ${formatPercent(percent)}%",
        fontSize = Typo.listMeta.size,
        color = colors.fg2,
      )
    }
    Box(
      Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(colors.track),
    ) {
      Box(
        Modifier
          .fillMaxWidth((percent / 100.0).toFloat().coerceIn(0f, 1f))
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(colors.primary),
      )
    }
  }
}

private val KIND_LABELS: Map<VoteKind, String> = mapOf(
  VoteKind.VOTE to "投票",
  VoteKind.BET to "投注",
  VoteKind.SCORE to "评分",
  VoteKind.SCORE_ENTRY to "评分",
  VoteKind.QA to "问答",
)

private fun summaryOf(vote: Vote, label: String): String {
  val parts = mutableListOf("共计 ${vote.voters} 人$label", "共计 ${vote.totalVotes} 票")
  parts.add("最多选择 ${vote.maxSelect} 项")
  vote.requirement?.let { parts.add(it) }
  vote.endAt?.let { parts.add("结束时间 ${formatEnd(it)}") }
  if (vote.resultAfterVote) parts.add("提交后可查看结果")
  if (vote.resultAfterEnd) parts.add("结束后可查看结果")
  return parts.joinToString(" · ")
}

private fun formatEnd(endAt: Long): String = END_FORMAT.format(
  Instant.ofEpochSecond(endAt).atOffset(ZoneOffset.ofHours(8)),
)

private val END_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatPercent(percent: Double): String =
  if (percent == percent.toLong().toDouble()) percent.toLong().toString() else "%.1f".format(percent)
