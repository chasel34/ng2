/**
 * core/local —— 纯客户端算法：服务端不提供、必须本机算出来的那些东西
 * （API 文档 §5 第 11 条）。纯 TS、零 RN 依赖，不发请求。
 */

export {
  decodeAnonymousName,
  isAnonymousAuthor,
  resolveAuthorName,
  type AnonymousName,
} from './anonymous'
export {
  advanceHistoryFloor,
  formatHistoryTime,
  HISTORY_LIMIT,
  historyProgressLabel,
  isHistoryFinished,
  pageOfFloor,
  upsertHistory,
  type HistoryEntry,
  type HistoryUpdate,
  type TopicVisit,
} from './history'
export {
  formatDiceTerms,
  resolveDice,
  type DiceOutcome,
  type DiceSeed,
  type DiceTerm,
} from './dice'
export {
  PLAIN_TITLE_STYLE,
  decodeTitleStyle,
  parseTopicMisc,
  titleStyleFromMask,
  type TitleColor,
  type TitleStyle,
  type TitleStyleSource,
  type TopicMisc,
} from './title-style'
export {
  isVoteClosed,
  parseVote,
  voteSharePercent,
  type ParseVoteOptions,
  type Vote,
  type VoteGroup,
  type VoteKind,
  type VoteOption,
} from './vote'
