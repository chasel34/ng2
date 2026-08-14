import * as SQLite from 'expo-sqlite';
import { create } from 'zustand';

import {
  advanceHistoryFloor,
  HISTORY_LIMIT,
  upsertHistory,
  type HistoryEntry,
  type HistoryUpdate,
  type TopicVisit,
} from '@/core/local';

/**
 * 浏览历史的 SQLite 适配器(spec §4:浏览历史属 expo-sqlite,不塞 MMKV)。
 *
 * LRU/去重/进度规则全在 `core/local/history`(纯 TS,带单测),这里只做两件事:
 * 启动时把库里的行灌进 zustand,每次变更后把「动过的那一条 + 被挤出去的行」写回去。
 * 内存里的数组是唯一事实来源,SQLite 只是它的落盘影子——读永远走 store,不查库。
 *
 * 例外是滚动上报的阅读进度:它一秒能来十几次,而 `withTransactionSync` 是**同步磁盘写**,
 * 直接落在滚动的那一帧上(M4 性能走查:详情页慢拖 54% janky frames)。所以进度先记在
 * 内存里,按 `FLOOR_FLUSH_INTERVAL_MS` 节流落盘,退出这一屏/退到后台时由调用方兜底
 * `flushReadFloor()`。语义没变:仍然只前进,仍然一定落盘,只是不在手指还按着的时候写。
 */

interface HistoryState {
  readonly entries: readonly HistoryEntry[];
}

export const useHistoryStore = create<HistoryState>()(() => ({
  entries: loadAll(),
}));

/** 订阅整张历史列表(历史页用);已按最近浏览排好序。 */
export const useHistoryEntries = (): readonly HistoryEntry[] =>
  useHistoryStore((state) => state.entries);

/** 同步读一条(详情页进场时取「上次读到」),读不到返回 undefined。 */
export function peekHistoryEntry(tid: number): HistoryEntry | undefined {
  return useHistoryStore.getState().entries.find((entry) => entry.tid === tid);
}

/** 进入主题(或翻页拿到新一页)时登记:去重、挪到最前、刷新元数据与时间。 */
export function recordTopicVisit(visit: TopicVisit): void {
  apply(upsertHistory(useHistoryStore.getState().entries, visit, nowSec()));
}

/** 阅读进度落盘的最小间隔。慢拖一秒能报十几次,这一档把磁盘写压到每秒一次。 */
const FLOOR_FLUSH_INTERVAL_MS = 1000;

/**
 * 还没落盘的阅读进度。滚动只改它,不碰 SQLite 也不碰 zustand。
 * `dirty` 为 false 表示已经落过盘了,水位线还留着是为了拦住重复上报。
 */
let pendingFloor: { tid: number; lou: number; dirty: boolean } | undefined;
let lastFloorFlushAt = 0;
let floorFlushTimer: ReturnType<typeof setTimeout> | undefined;

/**
 * 滚动时上报看到的楼层号。楼层没前进时是纯 no-op;前进了也只记在内存里,
 * 距上次落盘不足 `FLOOR_FLUSH_INTERVAL_MS` 就挂个尾巴定时器等会儿再写。
 */
export function recordReadFloor(tid: number, lou: number): void {
  // 换主题(自动加载下一页不换,但从缓存/深链跳到别的帖会)先把上一条落定
  if (pendingFloor !== undefined && pendingFloor.tid !== tid) flushReadFloor();
  // 只前进:回头翻前几楼不该反复触发落盘(core/local/history 也会再拦一次)
  if (pendingFloor !== undefined && pendingFloor.tid === tid && lou <= pendingFloor.lou) return;
  pendingFloor = { tid, lou, dirty: true };

  const elapsed = Date.now() - lastFloorFlushAt;
  if (elapsed >= FLOOR_FLUSH_INTERVAL_MS) {
    flushReadFloor();
    return;
  }
  // 手指停在半路就不动了也得落盘,所以尾巴这一发不能省
  floorFlushTimer ??= setTimeout(flushReadFloor, FLOOR_FLUSH_INTERVAL_MS - elapsed);
}

/**
 * 把内存里攒着的阅读进度写下去。退出详情页、退到后台时必须调一次——
 * 不然最后 1 秒读到的楼层会跟着页面一起丢。没有待落盘的东西时是纯 no-op。
 */
export function flushReadFloor(): void {
  if (floorFlushTimer !== undefined) {
    clearTimeout(floorFlushTimer);
    floorFlushTimer = undefined;
  }
  if (pendingFloor === undefined || !pendingFloor.dirty) return;
  const { tid, lou } = pendingFloor;
  lastFloorFlushAt = Date.now();

  const entries = useHistoryStore.getState().entries;
  // 条目得先由 recordTopicVisit 建好,不然 core 层会原样丢弃这次上报。
  // 那种情况下水位线也要一起丢,否则同一楼层再报进来会被上面的「只前进」拦掉
  if (!entries.some((entry) => entry.tid === tid)) {
    pendingFloor = undefined;
    return;
  }
  pendingFloor = { tid, lou, dirty: false };
  apply(advanceHistoryFloor(entries, tid, lou, nowSec()));
}

/** 清空浏览历史(历史页右上角 delete_sweep)。 */
export function clearHistory(): void {
  // 攒着的进度要丢掉而不是落盘:清完再写回去等于把删掉的条目又变出来
  if (floorFlushTimer !== undefined) {
    clearTimeout(floorFlushTimer);
    floorFlushTimer = undefined;
  }
  pendingFloor = undefined;
  useHistoryStore.setState({ entries: [] });
  db().runSync('DELETE FROM browse_history');
}

function apply(update: HistoryUpdate): void {
  if (!update.changed) return;
  useHistoryStore.setState({ entries: update.entries });

  // 每次变更只动一条,而且 upsert 和进度前进都会把它挪到最前——只写第一行,不用全表重写
  const changed = update.entries[0];
  const database = db();
  database.withTransactionSync(() => {
    for (const tid of update.evictedTids) {
      database.runSync('DELETE FROM browse_history WHERE tid = ?', [tid]);
    }
    if (changed !== undefined) upsertRow(database, changed);
  });
}

function upsertRow(database: SQLite.SQLiteDatabase, entry: HistoryEntry): void {
  database.runSync(
    `INSERT INTO browse_history (tid, subject, author, board_name, fav_code, last_floor, max_floor, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(tid) DO UPDATE SET
       subject = excluded.subject,
       author = excluded.author,
       board_name = excluded.board_name,
       fav_code = excluded.fav_code,
       last_floor = excluded.last_floor,
       max_floor = excluded.max_floor,
       updated_at = excluded.updated_at`,
    [
      entry.tid,
      entry.subject,
      entry.author ?? null,
      entry.boardName ?? null,
      entry.favCode ?? null,
      entry.lastFloor,
      entry.maxFloor,
      entry.updatedAt,
    ],
  );
}

interface HistoryRow {
  readonly tid: number;
  readonly subject: string;
  readonly author: string | null;
  readonly board_name: string | null;
  readonly fav_code: string | null;
  readonly last_floor: number;
  readonly max_floor: number;
  readonly updated_at: number;
}

function loadAll(): HistoryEntry[] {
  const rows = db().getAllSync<HistoryRow>(
    'SELECT * FROM browse_history ORDER BY updated_at DESC LIMIT ?',
    [HISTORY_LIMIT],
  );
  return rows.map((row) => ({
    tid: row.tid,
    subject: row.subject,
    lastFloor: row.last_floor,
    maxFloor: row.max_floor,
    updatedAt: row.updated_at,
    ...(row.author === null ? {} : { author: row.author }),
    ...(row.board_name === null ? {} : { boardName: row.board_name }),
    ...(row.fav_code === null ? {} : { favCode: row.fav_code }),
  }));
}

let database: SQLite.SQLiteDatabase | undefined;

/**
 * 库名沿用 MMKV 的 `ng2` 前缀;20 票的帖子缓存会共用这个库,各建各的表。
 * 懒打开:store 初始化第一次取数时才建连接。
 */
function db(): SQLite.SQLiteDatabase {
  if (database === undefined) {
    database = SQLite.openDatabaseSync('ng2.db');
    database.execSync(
      `PRAGMA journal_mode = WAL;
       CREATE TABLE IF NOT EXISTS browse_history (
         tid INTEGER PRIMARY KEY,
         subject TEXT NOT NULL,
         author TEXT,
         board_name TEXT,
         fav_code TEXT,
         last_floor INTEGER NOT NULL,
         max_floor INTEGER NOT NULL,
         updated_at INTEGER NOT NULL
       );
       CREATE INDEX IF NOT EXISTS idx_browse_history_updated_at ON browse_history (updated_at DESC);`,
    );
  }
  return database;
}

const nowSec = (): number => Math.floor(Date.now() / 1000);
