/**
 * core/api —— 在 core/net 之上，把 NGA 各接口的响应手工遍历成领域模型。
 *
 * 纯 TS、零 RN 依赖。响应里的字段随时可能缺、可能换类型，
 * 各解析器一律「坏条目跳过、整体不炸」;共用的取字段工具在 `fields.ts`。
 */

export { fetchBoardTree, parseBoardTree, pickActiveAnnouncement } from './board-tree'
export {
  BOARD_TREE_TTL_MS,
  isBoardTreeStale,
  loadBoardTree,
  mergeBoardTree,
  type BoardTreeLoadResult,
  type BoardTreeStore,
  type CachedBoardTree,
  type LoadBoardTreeOptions,
} from './board-tree-cache'
export {
  addBoardFavorite,
  clearBoardFavorites,
  fetchBoardFavorites,
  parseBoardFavorites,
  parseBoardIdInput,
  removeBoardFavorite,
} from './board-favor'
export {
  EMPTY_BLOCK_WORDS,
  blockWordError,
  blockWordRefererPath,
  fetchBlockWords,
  officialFilterRules,
  parseBlockWords,
  serializeBlockWords,
  setBlockWords,
  type BlockWordList,
  type BlockWordOptions,
  type BlockedUser,
  type SetBlockWordsOptions,
} from './block-word'
export {
  ATTACH_BASE_FALLBACK,
  attachmentUrl,
  normalizeAttachBase,
  stripThumbnailSuffix,
  type AttachmentUrlOptions,
} from './attachments'
export { checkIn, type CheckInResult } from './check-in'
export { int, nonZero, orderedEntries, orderedValues, str } from './fields'
export { updateSignature, type UpdateSignatureOptions } from './set-sign'
export {
  FILTERABLE_ATTRIBUTES_MIN,
  SUBSCRIBED_ATTRIBUTES,
  nextSubBoardState,
  setSubBoardOption,
  subBoardOptionParam,
  subBoardState,
  type SetSubBoardOptionOptions,
  type SubBoardAction,
  type SubBoardState,
} from './sub-board'
export {
  DEFAULT_HOT_PAGES,
  fetchHotTopicPages,
  type FetchHotTopicPagesOptions,
  type HotTopicPages,
} from './hot-topics'
export {
  fetchTopicDetail,
  parseAvatarUrl,
  parseTopicDetail,
  type CachedPageSnapshot,
  type FetchTopicDetailOptions,
  type ParseTopicDetailOptions,
} from './topic-detail'
export {
  clearNotificationFeed,
  fetchNotificationFeed,
  notificationKind,
  parseNotificationFeed,
} from './notifications'
export {
  addTopicFavorite,
  createFavoriteFolder,
  deleteFavoriteFolder,
  fetchFavoriteFolders,
  fetchFavoriteTopics,
  modifyFavoriteFolder,
  parseFavoriteFolders,
  removeTopicFavorite,
  type FavoriteFolder,
  type FetchFavoriteTopicsOptions,
} from './topic-favor'
export {
  expectedRecommendDelta,
  nextRecommendState,
  postRecommend,
  recommendStateOf,
  type PostRecommendOptions,
  type RecommendAction,
  type RecommendMark,
  type RecommendResult,
  type RecommendState,
} from './topic-recommend'
export {
  fetchTopicList,
  mergeTopicPages,
  parseTopicList,
  type FetchTopicListOptions,
  type TopicSort,
} from './topic-list'
export {
  fetchBoardSearch,
  fetchTopicSearch,
  parseBoardSearch,
  parseUserSearchInput,
  type BoardSearchItem,
  type FetchBoardSearchOptions,
  type FetchTopicSearchOptions,
  type UserSearchQuery,
} from './search'
export {
  UCP_REFERER_PATH,
  fetchUserAvatar,
  fetchUserProfile,
  fetchUserProfileByName,
  parseUserProfile,
  type FetchUserAvatarOptions,
  type FetchUserProfileByNameOptions,
  type FetchUserProfileOptions,
  type ParseUserProfileOptions,
} from './user-profile'
export {
  fetchUserTopics,
  hasMoreUserPosts,
  mergeUserPostPages,
  type FetchUserTopicsOptions,
  type UserPostKind,
} from './user-topics'
export type {
  AdminForum,
  Board,
  BoardCategory,
  BoardGroup,
  BoardTree,
  Floor,
  FloorAttachment,
  FloorClient,
  FloorUser,
  HomeAnnouncement,
  NgaNotification,
  NotificationFeed,
  NotificationKind,
  ReputationEntry,
  SubBoard,
  Topic,
  TopicDetail,
  TopicList,
  TopicParent,
  TopicReply,
  TopicShortcut,
  UserProfile,
  UserStatus,
} from './types'
