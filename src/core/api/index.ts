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
  ATTACH_BASE_FALLBACK,
  attachmentUrl,
  normalizeAttachBase,
  stripThumbnailSuffix,
  type AttachmentUrlOptions,
} from './attachments'
export { int, nonZero, orderedEntries, orderedValues, str } from './fields'
export {
  fetchTopicDetail,
  parseAvatarUrl,
  parseTopicDetail,
  type FetchTopicDetailOptions,
  type ParseTopicDetailOptions,
} from './topic-detail'
export {
  fetchTopicList,
  mergeTopicPages,
  parseTopicList,
  type FetchTopicListOptions,
  type TopicSort,
} from './topic-list'
export type {
  Board,
  BoardCategory,
  BoardGroup,
  BoardTree,
  Floor,
  FloorAttachment,
  FloorClient,
  FloorUser,
  HomeAnnouncement,
  Topic,
  TopicDetail,
  TopicList,
  TopicParent,
  TopicShortcut,
} from './types'
