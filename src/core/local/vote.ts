/**
 * 投票解析——楼层的 `vote` 字段（API 文档 §3）。
 *
 * 这个字段不是 BBCode，是一串 `~` 分隔的 key-value：
 *
 * ```text
 * 208133~华为~208134~美国高通~max_select~1~end~1793891155~_208133~123,0,138~_208134~15,0,0
 * └ 选项 id 与标题成对出现 ┘└──── 配置项 ────┘└── `_选项id` → 票数,投注量,总人数 ──┘
 * ```
 *
 * 拆法与字段含义照 **NGA 官方前端** `js_read.js` 的 `commonui.vote` / `voteFormat`
 * （https://img4.nga.cn/common_res/js_read.js）：
 * `__NUKE.scDe` 就是「按 `~` 切开，两两配对」；写入时会把标题里的 `~` 删掉，
 * 所以不存在转义，也就不需要为它做容错——落单的最后一段照官方的做法直接丢掉。
 *
 * spec §1 把**投票操作**排除在 v1 之外，这里只解析到只读渲染够用为止。
 */

/** `type` 字段：投票 / 投注 / 评分 / 单条评分 / 问答。 */
export type VoteKind = 'vote' | 'bet' | 'score' | 'scoreEntry' | 'qa'

const KINDS: readonly VoteKind[] = ['vote', 'bet', 'score', 'scoreEntry', 'qa']

export interface VoteOption {
  readonly id: string
  readonly title: string
  readonly votes: number
  /** 投注类的投注量、评分类的总分；普通投票恒为 0 */
  readonly points: number
  /** 当前账号投过这一项（`done`） */
  readonly chosen: boolean
}

/**
 * 一组选项。`===分组名===` 开头的选项在网页版是分隔行而不是可选项，
 * 它后面的选项自成一组、百分比按组内票数算——所以分组是解析的一部分，不是渲染的花样。
 */
export interface VoteGroup {
  /** 分隔行的原文（含 `===`）；没分组的投票只有一组，没有标题 */
  readonly title?: string
  readonly options: readonly VoteOption[]
  /** 组内票数合计，百分比的分母 */
  readonly votes: number
}

export interface Vote {
  readonly kind: VoteKind
  readonly groups: readonly VoteGroup[]
  readonly totalVotes: number
  /** 参与人数：各选项第三个数里的最大值 */
  readonly voters: number
  readonly maxSelect: number
  readonly multiple: boolean
  /** 秒级 unix 时间戳；没有截止时间时缺省 */
  readonly endAt?: number
  /** 评分类的分数区间 */
  readonly scoreRange?: { readonly min: number; readonly max: number }
  /** `opt&1`：提交后才能看结果 */
  readonly resultAfterVote: boolean
  /** `opt&2`：结束后才能看结果 */
  readonly resultAfterEnd: boolean
  /** `priv`：参与门槛，已按官方拼成「需要达到…版块声望以上」 */
  readonly requirement?: string
}

/** 分组分隔行的判据（官方 `x[k].til.substr(0,3)==='===' && tid>38056407`）。 */
const GROUP_MARK = '==='
const GROUP_MIN_TID = 38056407

const toInt = (value: string | undefined): number => {
  const parsed = Number.parseInt(value ?? '', 10)
  return Number.isNaN(parsed) ? 0 : parsed
}

/** 选项 id 是纯数字键；`_` 开头的是它的计数，其余是配置项。 */
const isOptionId = (key: string): boolean => /^[1-9]\d*$/.test(key)

export interface ParseVoteOptions {
  /** 分组语法只在这个 tid 之后的帖子里生效，老帖的 `===` 是普通选项 */
  readonly tid: number
}

/**
 * 解析楼层的 `vote` 串。空串、只有配置项没有选项、或结构坏掉时返回 undefined，
 * 楼层就当没有投票渲染——服务端这个字段随时可能是 `0` 或空。
 */
export function parseVote(raw: string, options: ParseVoteOptions): Vote | undefined {
  const parts = raw.split('~')
  if (parts.length < 2) return undefined

  const fields = new Map<string, string>()
  for (let i = 0; i + 1 < parts.length; i += 2) fields.set(parts[i]!, parts[i + 1]!)

  const chosen = new Set((fields.get('done') ?? '').split(',').filter((id) => id !== ''))

  interface DraftGroup {
    title?: string
    options: VoteOption[]
    votes: number
  }

  const groups: DraftGroup[] = []
  let voters = 0
  let totalVotes = 0

  for (const [key, title] of fields) {
    if (!isOptionId(key)) continue
    const counts = (fields.get(`_${key}`) ?? '').split(',')
    const votes = toInt(counts[0])
    // 第三个数只有第一条有值，是总人数
    voters = Math.max(voters, toInt(counts[2]))

    if (title.startsWith(GROUP_MARK) && options.tid > GROUP_MIN_TID) {
      groups.push({ title, options: [], votes: 0 })
      continue
    }

    let current = groups[groups.length - 1]
    if (current === undefined) {
      current = { options: [], votes: 0 }
      groups.push(current)
    }
    current.options.push({
      id: key,
      title,
      votes,
      points: toInt(counts[1]),
      chosen: chosen.has(key),
    })
    current.votes += votes
    totalVotes += votes
  }

  if (groups.every((group) => group.options.length === 0)) return undefined

  const kind = KINDS[toInt(fields.get('type'))] ?? 'vote'
  const maxSelect = Math.max(1, toInt(fields.get('max_select')))
  const endAt = toInt(fields.get('end'))
  const opt = toInt(fields.get('opt'))
  const min = toInt(fields.get('min'))
  const max = toInt(fields.get('max'))
  const requirement = fields.get('priv')

  return {
    kind,
    groups: groups.map((group) => ({
      ...(group.title === undefined ? {} : { title: group.title }),
      options: group.options,
      votes: group.votes,
    })),
    totalVotes,
    voters,
    maxSelect,
    multiple: maxSelect > 1,
    ...(endAt === 0 ? {} : { endAt }),
    ...(kind === 'score' ? { scoreRange: { min, max } } : {}),
    resultAfterVote: (opt & 1) !== 0,
    resultAfterEnd: (opt & 2) !== 0,
    ...(requirement === undefined || requirement === ''
      ? {}
      : { requirement: `${requirement.replace(/r-?\d+_/, '需要达到')}版块声望以上` }),
  }
}

/** 投票是否已结束（官方 `atv = !x.end || __NOW <= x.end` 的反面）。 */
export function isVoteClosed(vote: Vote, now: number): boolean {
  return vote.endAt !== undefined && now > vote.endAt
}

/** 票数占比，与网页版同样只保留一位小数（`((num/sum*1000)|0)/10`）。 */
export function voteSharePercent(votes: number, total: number): number {
  if (total <= 0) return 0
  return Math.trunc((votes / total) * 1000) / 10
}
