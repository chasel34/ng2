import * as SQLite from 'expo-sqlite';
import { create } from 'zustand';

import type { CachedPageSnapshot } from '@/core/api';
import {
  planCacheEviction,
  summarizeCachedPages,
  utf8ByteLength,
  type CachedPage,
  type CachedTopic,
} from '@/core/local';
import type { TopicCacheKey } from '@/core/net';

/**
 * 帖子缓存的 SQLite 适配器(spec §4:缓存自动写 SQLite + LRU 上限)。
 *
 * 表的粒度是「主题的一页」,一行存一页,正文是序列化后的信封
 * (`core/net/strategies/topic-cache` 的 `serializeEnvelope`)——原样喂回缓存档
 * 就能还原出与在线那条路同构的信封。
 *
 * 内存里只留元数据(标题/页码/字节数/时间),**正文永远留在库里**:
 * 一页十几万字符,几百页全灌进 zustand 会把内存吃光。所以这里与
 * `store/history.ts` 的写法不同——那边内存是唯一事实来源,这边库才是。
 */

interface TopicCacheState {
  /** 全部已缓存页的元数据(不含正文) */
  readonly pages: readonly CachedPage[];
  /** 按主题聚合后的展示用列表,已按最近使用倒序 */
  readonly topics: readonly CachedTopic[];
}

export const useTopicCacheStore = create<TopicCacheState>()(() => fromPages(loadMeta()));

/** 订阅「我的缓存」列表。 */
export const useCachedTopics = (): readonly CachedTopic[] =>
  useTopicCacheStore((state) => state.topics);

/** 某一页在不在缓存里(详情页「缓存本页」判断要不要真去拉一次)。 */
export function isPageCached(tid: number, page: number): boolean {
  return useTopicCacheStore
    .getState()
    .pages.some((entry) => entry.tid === tid && entry.page === page);
}

/** 一个主题缓存了哪些页(「缓存整帖」跳过已有的页)。 */
export function cachedPagesOf(tid: number): readonly number[] {
  return useTopicCacheStore.getState().topics.find((topic) => topic.tid === tid)?.pages ?? [];
}

/**
 * 反封锁链缓存档的读口(`createTopicCacheStrategy` 的 store)。
 *
 * 读到就顺手把 `used_at` 推到现在:LRU 说的是「最久未**用**」,只按写入时间淘汰的话,
 * 天天离线翻的那个帖会先被新缓存挤掉。
 */
export function readCachedPage(key: TopicCacheKey): string | undefined {
  const row = db().getFirstSync<{ payload: string }>(
    'SELECT payload FROM topic_cache WHERE tid = ? AND page = ?',
    [key.tid, key.page],
  );
  if (row === null || row === undefined) return undefined;
  touch(key.tid);
  return row.payload;
}

/**
 * 写一页缓存(浏览成功即自动写,`fetchTopicDetail` 的 onSnapshot)。
 *
 * 写完按 LRU 淘汰到上限内。**吞掉自己的异常**:缓存写失败不该连累用户正在读的这一页。
 */
export function saveCachedPage(snapshot: CachedPageSnapshot): void {
  try {
    const entry: CachedPage = {
      tid: snapshot.tid,
      page: snapshot.page,
      subject: snapshot.subject,
      floors: snapshot.floors,
      totalPages: snapshot.totalPages,
      bytes: utf8ByteLength(snapshot.payload),
      usedAt: nowSec(),
      ...(snapshot.boardName === undefined ? {} : { boardName: snapshot.boardName }),
      ...(snapshot.favCode === undefined ? {} : { favCode: snapshot.favCode }),
    };

    const database = db();
    database.runSync(
      `INSERT INTO topic_cache
         (tid, page, subject, board_name, fav_code, floors, total_pages, bytes, payload, used_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(tid, page) DO UPDATE SET
         subject = excluded.subject,
         board_name = excluded.board_name,
         fav_code = excluded.fav_code,
         floors = excluded.floors,
         total_pages = excluded.total_pages,
         bytes = excluded.bytes,
         payload = excluded.payload,
         used_at = excluded.used_at`,
      [
        entry.tid,
        entry.page,
        entry.subject,
        entry.boardName ?? null,
        entry.favCode ?? null,
        entry.floors,
        entry.totalPages,
        entry.bytes,
        snapshot.payload,
        entry.usedAt,
      ],
    );

    const kept = useTopicCacheStore
      .getState()
      .pages.filter((page) => page.tid !== entry.tid || page.page !== entry.page);
    evictAndSet([...kept, entry]);
  } catch {
    // 磁盘满、库被锁……缓存写不进去就算了,别把异常抛回正在渲染的详情页
  }
}

/** 删掉一个主题的全部缓存页(缓存页行尾的删除钮)。 */
export function deleteCachedTopic(tid: number): void {
  db().runSync('DELETE FROM topic_cache WHERE tid = ?', [tid]);
  const pages = useTopicCacheStore.getState().pages.filter((page) => page.tid !== tid);
  useTopicCacheStore.setState(fromPages(pages));
}

/** 清空全部缓存(缓存页右上角 delete_sweep)。 */
export function clearTopicCache(): void {
  db().runSync('DELETE FROM topic_cache');
  useTopicCacheStore.setState({ pages: [], topics: [] });
}

/** 把某个主题的全部页标成「刚用过」,免得正在离线读的帖被 LRU 挤掉。 */
function touch(tid: number): void {
  const at = nowSec();
  db().runSync('UPDATE topic_cache SET used_at = ? WHERE tid = ?', [at, tid]);
  const pages = useTopicCacheStore
    .getState()
    .pages.map((page) => (page.tid === tid ? { ...page, usedAt: at } : page));
  useTopicCacheStore.setState(fromPages(pages));
}

/** 淘汰到上限内并落盘。淘汰口径(整主题淘汰、留住最近用的那个)在 core/local。 */
function evictAndSet(pages: readonly CachedPage[]): void {
  const next = fromPages(pages);
  const evicted = planCacheEviction(next.topics);
  if (evicted.length === 0) {
    useTopicCacheStore.setState(next);
    return;
  }

  const database = db();
  database.withTransactionSync(() => {
    for (const tid of evicted) database.runSync('DELETE FROM topic_cache WHERE tid = ?', [tid]);
  });
  const dropped = new Set(evicted);
  useTopicCacheStore.setState(fromPages(pages.filter((page) => !dropped.has(page.tid))));
}

function fromPages(pages: readonly CachedPage[]): TopicCacheState {
  return { pages, topics: summarizeCachedPages(pages) };
}

interface CacheRow {
  readonly tid: number;
  readonly page: number;
  readonly subject: string;
  readonly board_name: string | null;
  readonly fav_code: string | null;
  readonly floors: number;
  readonly total_pages: number;
  readonly bytes: number;
  readonly used_at: number;
}

/** 启动时把元数据灌进内存;`payload` 一列刻意不选,它可能有几十 MB。 */
function loadMeta(): readonly CachedPage[] {
  const rows = db().getAllSync<CacheRow>(
    `SELECT tid, page, subject, board_name, fav_code, floors, total_pages, bytes, used_at
     FROM topic_cache`,
  );
  return rows.map((row) => ({
    tid: row.tid,
    page: row.page,
    subject: row.subject,
    floors: row.floors,
    totalPages: row.total_pages,
    bytes: row.bytes,
    usedAt: row.used_at,
    ...(row.board_name === null ? {} : { boardName: row.board_name }),
    ...(row.fav_code === null ? {} : { favCode: row.fav_code }),
  }));
}

let database: SQLite.SQLiteDatabase | undefined;

/** 与浏览历史共用 `ng2.db`(见 store/history.ts),各建各的表。 */
function db(): SQLite.SQLiteDatabase {
  if (database === undefined) {
    database = SQLite.openDatabaseSync('ng2.db');
    database.execSync(
      `PRAGMA journal_mode = WAL;
       CREATE TABLE IF NOT EXISTS topic_cache (
         tid INTEGER NOT NULL,
         page INTEGER NOT NULL,
         subject TEXT NOT NULL,
         board_name TEXT,
         fav_code TEXT,
         floors INTEGER NOT NULL,
         total_pages INTEGER NOT NULL,
         bytes INTEGER NOT NULL,
         payload TEXT NOT NULL,
         used_at INTEGER NOT NULL,
         PRIMARY KEY (tid, page)
       );
       CREATE INDEX IF NOT EXISTS idx_topic_cache_used_at ON topic_cache (used_at DESC);`,
    );
  }
  return database;
}

const nowSec = (): number => Math.floor(Date.now() / 1000);
