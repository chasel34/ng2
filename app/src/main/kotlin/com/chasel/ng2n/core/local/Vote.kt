package com.chasel.ng2n.core.local

enum class VoteKind { VOTE, BET, SCORE, SCORE_ENTRY, QA }

private val KINDS = listOf(VoteKind.VOTE, VoteKind.BET, VoteKind.SCORE, VoteKind.SCORE_ENTRY, VoteKind.QA)

data class VoteOption(
  val id: String,
  val title: String,
  val votes: Long,
  val points: Long,
  val chosen: Boolean,
)

data class VoteGroup(
  val title: String? = null,
  val options: List<VoteOption> = emptyList(),
  val votes: Long = 0L,
)

data class VoteScoreRange(val min: Long, val max: Long)

data class Vote(
  val kind: VoteKind,
  val groups: List<VoteGroup>,
  val totalVotes: Long,
  val voters: Long,
  val maxSelect: Long,
  val multiple: Boolean,
  /** Unix 秒时间戳；null 表示不限时。 */
  val endAt: Long? = null,
  val scoreRange: VoteScoreRange? = null,
  val resultAfterVote: Boolean = false,
  val resultAfterEnd: Boolean = false,
  val requirement: String? = null,
)

private const val GROUP_MARK = "==="
private const val GROUP_MIN_TID = 38056407L

private val OPTION_ID = Regex("""^[1-9]\d*$""")

private val PRIV_PREFIX = Regex("""r-?\d+_""")

private fun toInt(value: String?): Long {
  if (value == null) return 0L
  var index = 0
  while (index < value.length && value[index].isJsWhitespace()) index++
  var negative = false
  if (index < value.length && (value[index] == '+' || value[index] == '-')) {
    negative = value[index] == '-'
    index++
  }
  val start = index
  while (index < value.length && value[index] in '0'..'9') index++
  if (index == start) return 0L
  val digits = value.substring(start, index)
  val magnitude = digits.toLongOrNull() ?: return 0L
  return if (negative) -magnitude else magnitude
}

fun parseVote(raw: String, tid: Long): Vote? {
  val parts = raw.split("~")
  if (parts.size < 2) return null

  val fields = LinkedHashMap<String, String>()
  var i = 0
  while (i + 1 < parts.size) {
    fields[parts[i]] = parts[i + 1]
    i += 2
  }

  val chosen = (fields["done"] ?: "").split(",").filter { it.isNotEmpty() }.toHashSet()

  class DraftGroup(var title: String? = null) {
    val options = ArrayList<VoteOption>()
    var votes = 0L
  }

  val groups = ArrayList<DraftGroup>()
  var voters = 0L
  var totalVotes = 0L

  for ((key, title) in fields) {
    if (!OPTION_ID.matches(key)) continue
    val counts = (fields["_$key"] ?: "").split(",")
    val votes = toInt(counts.getOrNull(0))
    voters = maxOf(voters, toInt(counts.getOrNull(2)))

    if (title.startsWith(GROUP_MARK) && tid > GROUP_MIN_TID) {
      groups.add(DraftGroup(title))
      continue
    }

    val current = groups.lastOrNull() ?: DraftGroup().also { groups.add(it) }
    current.options.add(
      VoteOption(
        id = key,
        title = title,
        votes = votes,
        points = toInt(counts.getOrNull(1)),
        chosen = key in chosen,
      ),
    )
    current.votes += votes
    totalVotes += votes
  }

  if (groups.all { it.options.isEmpty() }) return null

  val kind = KINDS.getOrNull(toInt(fields["type"]).toInt()) ?: VoteKind.VOTE
  val maxSelect = maxOf(1L, toInt(fields["max_select"]))
  val endAt = toInt(fields["end"])
  val opt = toInt(fields["opt"])
  val min = toInt(fields["min"])
  val max = toInt(fields["max"])
  val requirement = fields["priv"]

  return Vote(
    kind = kind,
    groups = groups.map { VoteGroup(it.title, it.options.toList(), it.votes) },
    totalVotes = totalVotes,
    voters = voters,
    maxSelect = maxSelect,
    multiple = maxSelect > 1L,
    endAt = if (endAt == 0L) null else endAt,
    scoreRange = if (kind == VoteKind.SCORE) VoteScoreRange(min, max) else null,
    resultAfterVote = (opt and 1L) != 0L,
    resultAfterEnd = (opt and 2L) != 0L,
    requirement = if (requirement.isNullOrEmpty()) {
      null
    } else {
      "${PRIV_PREFIX.replaceFirst(requirement, "需要达到")}版块声望以上"
    },
  )
}

fun isVoteClosed(vote: Vote, now: Long): Boolean = vote.endAt != null && now > vote.endAt

fun voteSharePercent(votes: Long, total: Long): Double {
  if (total <= 0L) return 0.0
  return kotlin.math.truncate(votes.toDouble() / total.toDouble() * 1000.0) / 10.0
}
