package com.chasel.ng2n.core.local

/**
 * 投票解析——楼层的 `vote` 字段(API 文档 §3)。直译 `src/core/local/vote.ts`。
 *
 * 这个字段不是 BBCode,是一串 `~` 分隔的 key-value:
 *
 * ```text
 * 208133~华为~208134~美国高通~max_select~1~end~1793891155~_208133~123,0,138~_208134~15,0,0
 * └ 选项 id 与标题成对出现 ┘└──── 配置项 ────┘└── `_选项id` → 票数,投注量,总人数 ──┘
 * ```
 *
 * 拆法与字段含义照 **NGA 官方前端** `js_read.js` 的 `commonui.vote` / `voteFormat`:
 * `__NUKE.scDe` 就是「按 `~` 切开,两两配对」;写入时会把标题里的 `~` 删掉,
 * 所以不存在转义,也就不需要为它做容错——落单的最后一段照官方的做法直接丢掉。
 *
 * spec §1 把**投票操作**排除在 v1 之外,这里只解析到只读渲染够用为止。
 */

/** `type` 字段:投票 / 投注 / 评分 / 单条评分 / 问答。 */
enum class VoteKind { VOTE, BET, SCORE, SCORE_ENTRY, QA }

/** `type` 的取值就是这张表的下标,顺序不能动。 */
private val KINDS = listOf(VoteKind.VOTE, VoteKind.BET, VoteKind.SCORE, VoteKind.SCORE_ENTRY, VoteKind.QA)

data class VoteOption(
  val id: String,
  val title: String,
  val votes: Long,
  /** 投注类的投注量、评分类的总分;普通投票恒为 0 */
  val points: Long,
  /** 当前账号投过这一项(`done`) */
  val chosen: Boolean,
)

/**
 * 一组选项。`===分组名===` 开头的选项在网页版是分隔行而不是可选项,
 * 它后面的选项自成一组、百分比按组内票数算——所以分组是解析的一部分,不是渲染的花样。
 */
data class VoteGroup(
  /** 分隔行的原文(含 `===`);没分组的投票只有一组,没有标题 */
  val title: String? = null,
  val options: List<VoteOption> = emptyList(),
  /** 组内票数合计,百分比的分母 */
  val votes: Long = 0L,
)

data class VoteScoreRange(val min: Long, val max: Long)

data class Vote(
  val kind: VoteKind,
  val groups: List<VoteGroup>,
  val totalVotes: Long,
  /** 参与人数:各选项第三个数里的最大值 */
  val voters: Long,
  val maxSelect: Long,
  val multiple: Boolean,
  /** 秒级 unix 时间戳;没有截止时间时是 `null` */
  val endAt: Long? = null,
  /** 评分类的分数区间 */
  val scoreRange: VoteScoreRange? = null,
  /** `opt&1`:提交后才能看结果 */
  val resultAfterVote: Boolean = false,
  /** `opt&2`:结束后才能看结果 */
  val resultAfterEnd: Boolean = false,
  /** `priv`:参与门槛,已按官方拼成「需要达到…版块声望以上」 */
  val requirement: String? = null,
)

/** 分组分隔行的判据(官方 `x[k].til.substr(0,3)==='===' && tid>38056407`)。 */
private const val GROUP_MARK = "==="
private const val GROUP_MIN_TID = 38056407L

/** 选项 id 是纯数字键;`_` 开头的是它的计数,其余是配置项。 */
private val OPTION_ID = Regex("""^[1-9]\d*$""")

/** `priv` 的 `r数字_` 前缀(门槛数值在下划线之后)。 */
private val PRIV_PREFIX = Regex("""r-?\d+_""")

/**
 * JS 的 `Number.parseInt(value ?? '', 10)`,解不出来算 0。
 *
 * 与 Kotlin 的 `toLongOrNull` 不同:JS 的 parseInt **吃到非数字为止**
 * (`"123,0,138"` → 123),缺字段/空串给 NaN,调用点一律折成 0。
 */
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

/**
 * 解析楼层的 `vote` 串。空串、只有配置项没有选项、或结构坏掉时返回 `null`,
 * 楼层就当没有投票渲染——服务端这个字段随时可能是 `0` 或空。
 *
 * @param tid 分组语法只在这个 tid 之后的帖子里生效,老帖的 `===` 是普通选项
 */
fun parseVote(raw: String, tid: Long): Vote? {
  val parts = raw.split("~")
  if (parts.size < 2) return null

  // 顺序有意义:选项按串里出现的先后成组,所以是保序 map(JS 的 Map 同理)
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
    // 第三个数只有第一条有值,是总人数
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

/** 投票是否已结束(官方 `atv = !x.end || __NOW <= x.end` 的反面)。 */
fun isVoteClosed(vote: Vote, now: Long): Boolean = vote.endAt != null && now > vote.endAt

/** 票数占比,与网页版同样只保留一位小数(`((num/sum*1000)|0)/10`)。 */
fun voteSharePercent(votes: Long, total: Long): Double {
  if (total <= 0L) return 0.0
  return kotlin.math.truncate(votes.toDouble() / total.toDouble() * 1000.0) / 10.0
}
