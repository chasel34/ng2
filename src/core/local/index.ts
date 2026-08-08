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
  HOT_WINDOW_HOURS,
  aggregateHotTopics,
  type AggregateHotTopicsOptions,
  type HotTopicCandidate,
} from './hot-topics'
export {
  groupNotifications,
  markRead,
  mergeNotifications,
  newNotifications,
  notificationId,
  unreadCount,
  type NotificationIdParts,
  type NotificationLike,
} from './notifications'
export {
  REPUTATION_SCALE,
  formatMoney,
  formatReputation,
  splitMoney,
  toReputation,
  type Money,
} from './money'
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
  EMPTY_TOPIC_FAVOR_INDEX,
  applyFavoriteChange,
  diffFolderSelection,
  foldersOfTopic,
  parseTopicFavorIndex,
  pruneFolders,
  seedFolderTopics,
  type FavoriteChange,
  type FolderSelectionDiff,
  type SeedFolderTopicsOptions,
  type TopicFavorIndex,
} from './topic-favor-index'
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
