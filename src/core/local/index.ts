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
  EMPTY_CHECK_IN_DAYS,
  beijingDayKey,
  isCheckedInOn,
  markCheckedIn,
  parseCheckInDays,
  serializeCheckInDays,
  type CheckInDays,
} from './check-in'
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
  APP_SCHEME,
  NGA_LINK_FAILURE_MESSAGES,
  ngaLinkPath,
  parseNgaLink,
  type NgaBoardLink,
  type NgaLink,
  type NgaLinkFailure,
  type NgaLinkResult,
  type NgaTopicLink,
} from './deep-link'
export {
  FILTER_KIND_LABELS,
  compileFilterRegex,
  createFilterRule,
  filterMatchText,
  filterRuleId,
  matchFilterRules,
  normalizeRuleValue,
  removeFilterRule,
  topicCategories,
  upsertFilterRule,
  validateFilterRule,
  type FilterRule,
  type FilterRuleInput,
  type FilterRuleKind,
  type FilterRuleOrigin,
  type FilterSubject,
} from './filters'
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
  TOPIC_CACHE_MAX_BYTES,
  TOPIC_CACHE_MAX_TOPICS,
  cachePagesLabel,
  cacheTotalBytes,
  formatCacheSize,
  planCacheEviction,
  summarizeCachedPages,
  utf8ByteLength,
  type CacheLimits,
  type CachedPage,
  type CachedTopic,
} from './topic-cache'
export {
  APPEARANCE_SLIDERS,
  AVATAR_BASE_SIZE,
  DEFAULT_SETTINGS,
  IMAGE_QUALITY_LABELS,
  SMILEY_BASE_HEIGHT,
  THEME_STYLE_LABELS,
  avatarSizeOf,
  clampSlider,
  formatSliderValue,
  parseSettings,
  sliderRatio,
  sliderValueAt,
  smileyHeightOf,
  type AppSettings,
  type AppearanceSettings,
  type ImageQuality,
  type SliderSpec,
  type ThemeStyle,
} from './settings'
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
