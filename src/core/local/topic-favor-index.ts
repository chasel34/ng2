/**
 * 「这个主题在哪几个收藏夹里」的本机索引。
 *
 * **为什么要本机记**:`topic_favor_v2` 只给得出「某个夹里有哪些主题」
 * (`thread.php?favor=<夹id>`),给不出反向的「某个主题在哪几个夹里」——
 * MNGA 与官方 Android 端也都没有这个接口(docs/research/mnga-report.md §E)。
 * 而多选收藏对话框必须先知道当前勾了哪几个夹,才谈得上「取消单夹不影响其他夹」。
 *
 * 逐夹翻页去反查是不行的:一个夹上百个主题就是好几页,20 个夹就是几十个请求,
 * 正撞在 NGA 封第三方客户端的枪口上(ADR-0002)。所以改成**只记本机看得见的那部分**:
 *
 * - 收藏/取消收藏成功后,按结果改索引(这条最准,是用户刚做的事)
 * - 打开某个收藏夹的主题列表时,把那一页的 tid 一并记下(`seedFolderTopics`)
 *
 * 于是索引是「宁缺勿滥」的:记着的一定对,没记着的**未必**没收藏——
 * 别处(网页版、其他客户端)收的帖子,本机没见过就勾不上。对话框要把这层说清楚。
 */

/** tid → 该主题所属的收藏夹 id(本机已知的那部分),夹 id 升序且不重复。 */
export type TopicFavorIndex = Readonly<Record<string, readonly number[]>>

export const EMPTY_TOPIC_FAVOR_INDEX: TopicFavorIndex = {}

/** 夹 id 去重升序——索引里存的顺序固定,比较两份索引才有意义。 */
const normalizeFolderIds = (ids: Iterable<number>): number[] =>
  [...new Set(ids)].filter((id) => Number.isInteger(id) && id > 0).sort((a, b) => a - b)

/**
 * 「没记录过」返回的那个空数组。**必须是同一个实例**:
 * `foldersOfTopic` 会被当成 Zustand 的 selector 在 render 里调,
 * 每次返回一个新 `[]` 的话 `useSyncExternalStore` 会认为快照一直在变而空转。
 */
const NO_FOLDERS: readonly number[] = []

/** 本机已知的、这个主题所属的收藏夹 id。没记录过就是空数组。 */
export function foldersOfTopic(index: TopicFavorIndex, tid: number): readonly number[] {
  return index[String(tid)] ?? NO_FOLDERS
}

/** 写一条 tid 的归属;夹列表空了就把这条删掉,免得索引里攒一堆空数组。 */
function withTopic(index: TopicFavorIndex, tid: number, folderIds: readonly number[]): TopicFavorIndex {
  const key = String(tid)
  if (folderIds.length === 0) {
    if (!(key in index)) return index
    const { [key]: _dropped, ...rest } = index
    return rest
  }
  return { ...index, [key]: folderIds }
}

export interface FavoriteChange {
  readonly tid: number
  readonly folderId: number
  /** true = 刚加进这个夹,false = 刚移出 */
  readonly favored: boolean
}

/** 一次收藏/取消收藏落到索引上。服务端已经确认过了才调,所以直接覆盖。 */
export function applyFavoriteChange(index: TopicFavorIndex, change: FavoriteChange): TopicFavorIndex {
  const current = foldersOfTopic(index, change.tid)
  const next = change.favored
    ? normalizeFolderIds([...current, change.folderId])
    : current.filter((id) => id !== change.folderId)
  return withTopic(index, change.tid, next)
}

export interface SeedFolderTopicsOptions {
  readonly folderId: number
  /** 这一次列表里看到的 tid */
  readonly tids: readonly number[]
  /**
   * 这一批是不是这个夹的**全部**主题(列表只有一页时才为真)。
   * 为真才能反过来断言「没出现在里面的就是不在这个夹里」,把过期记录清掉。
   */
  readonly complete?: boolean
}

/**
 * 拿某个收藏夹的主题列表喂索引。
 *
 * 默认只做加法:只翻了一页的话,没出现在这页里的主题可能在后面几页,不能当成「不在这个夹」。
 * `complete` 为真(整个夹就这一页)时才连带清掉本机记错的归属。
 */
export function seedFolderTopics(
  index: TopicFavorIndex,
  { folderId, tids, complete = false }: SeedFolderTopicsOptions,
): TopicFavorIndex {
  const seen = new Set(tids)
  let next = index

  for (const tid of seen) {
    next = applyFavoriteChange(next, { tid, folderId, favored: true })
  }
  if (!complete) return next

  // 整个夹都在手上了:本机记着属于这个夹、却没出现在列表里的,是过期记录
  for (const [key, folderIds] of Object.entries(next)) {
    if (!folderIds.includes(folderId) || seen.has(Number(key))) continue
    next = withTopic(next, Number(key), folderIds.filter((id) => id !== folderId))
  }
  return next
}

/**
 * 删掉已经不存在的收藏夹留下的归属记录(用户删夹之后调)。
 * 传的是服务端最新的夹 id 全集。
 */
export function pruneFolders(index: TopicFavorIndex, existingFolderIds: Iterable<number>): TopicFavorIndex {
  const alive = new Set(existingFolderIds)
  let next = index
  for (const [key, folderIds] of Object.entries(index)) {
    const kept = folderIds.filter((id) => alive.has(id))
    if (kept.length !== folderIds.length) next = withTopic(next, Number(key), kept)
  }
  return next
}

export interface FolderSelectionDiff {
  /** 要调 `add` 的夹 */
  readonly added: readonly number[]
  /** 要调 `del` 的夹 */
  readonly removed: readonly number[]
}

/**
 * 对话框点「完成」时,把勾选前后的差算出来——只对改动过的夹发请求,
 * 没动过的夹一个请求都不发(重复 add 会把 `length` 算重)。
 */
export function diffFolderSelection(
  before: readonly number[],
  after: readonly number[],
): FolderSelectionDiff {
  const had = new Set(before)
  const has = new Set(after)
  return {
    added: normalizeFolderIds([...has].filter((id) => !had.has(id))),
    removed: normalizeFolderIds([...had].filter((id) => !has.has(id))),
  }
}

/**
 * 从落盘的 JSON 还原索引。存储里的东西一律当外部输入校验:
 * 可能是别的版本的 app 写下的,坏条目跳过而不是整份丢掉。
 */
export function parseTopicFavorIndex(raw: unknown): TopicFavorIndex {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return EMPTY_TOPIC_FAVOR_INDEX
  const index: Record<string, readonly number[]> = {}
  for (const [key, value] of Object.entries(raw)) {
    const tid = Number(key)
    if (!Number.isInteger(tid) || tid <= 0 || !Array.isArray(value)) continue
    const folderIds = normalizeFolderIds(value.filter((id): id is number => typeof id === 'number'))
    if (folderIds.length > 0) index[String(tid)] = folderIds
  }
  return index
}
