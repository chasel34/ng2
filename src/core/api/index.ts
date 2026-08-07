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
export { int, nonZero, orderedEntries, orderedValues, str } from './fields'
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
  HomeAnnouncement,
  Topic,
  TopicList,
  TopicParent,
  TopicShortcut,
} from './types'
