import { openDatabaseSync, type SQLiteDatabase } from 'expo-sqlite';
import { useEffect } from 'react';
import { AppState } from 'react-native';
import { create } from 'zustand';

import {
  clearNotificationFeed,
  fetchNotificationFeed,
  type NgaNotification,
} from '@/core/api';
import { markRead, mergeNotifications, unreadCount } from '@/core/local';

import { useAccounts } from './accounts';
import { fetchNga } from './nga-client';
import { useSettings } from './settings';

/** 前台轮询间隔(spec §4:登录后前台每 60s 轮询,无系统通知/后台任务)。 */
export const NOTIFICATION_POLL_MS = 60_000;

/**
 * 一直没有新通知时,跳过几格再拉。索引 = 连续拉空的次数。
 *
 * 前台常驻每分钟一发 `nuke.php`,和用户自己的浏览抢同一份服务端配额,而封第三方
 * 客户端是常态(ADR-0002)——一个整天挂着、其实没人理的账号,一小时打 60 发
 * 只为了一个角标,性价比太低。所以拉空就退避:60s → 120s → 180s → 300s。
 *
 * **退避靠跳格而不是改周期**:定时器仍然是稳稳的 60s 一格,只是到点了不一定发请求。
 * 这样 start/stop 那套(退后台即停)一个字都不用动,也没有「改周期要重建定时器」
 * 带来的竞态。
 *
 * 回到 60s 的条件有两个:拉到了新通知,或者 app 刚回到前台(那时用户大概率就是
 * 冲着看消息来的)。另外通知页自己会主动拉一次,不受这里影响。
 */
const POLL_BACKOFF_SKIPS = [0, 1, 2, 4] as const;

/**
 * 通知的设备侧落地:core/local 的已读模型进 Zustand,已读 ID 持久化 expo-sqlite
 * (spec §3:通知已读走 sqlite,不塞 MMKV)。
 *
 * 已读按账号分桶(uid + id 联合主键):切号后各看各的已读,互不污染。
 * 条目列表本身不持久化——get_all 每次都返回近期全量,已读靠稳定 ID 对上号。
 */
const db: SQLiteDatabase | null = (() => {
  try {
    const opened = openDatabaseSync('notifications.db');
    opened.execSync(
      `CREATE TABLE IF NOT EXISTS notification_read (
        uid TEXT NOT NULL,
        id TEXT NOT NULL,
        read_at INTEGER NOT NULL,
        PRIMARY KEY (uid, id)
      );`,
    );
    return opened;
  } catch {
    // 打不开(web 预览/存储损坏)就只活在内存——已读退化成本次会话内有效
    return null;
  }
})();

function loadReadIds(uid: string): ReadonlySet<string> {
  if (db === null) return new Set();
  try {
    const rows = db.getAllSync<{ id: string }>(
      'SELECT id FROM notification_read WHERE uid = ?',
      [uid],
    );
    return new Set(rows.map((row) => row.id));
  } catch {
    return new Set();
  }
}

function persistReadIds(uid: string, ids: readonly string[]): void {
  if (db === null || ids.length === 0) return;
  try {
    db.withTransactionSync(() => {
      for (const id of ids) {
        db.runSync(
          'INSERT OR IGNORE INTO notification_read (uid, id, read_at) VALUES (?, ?, ?)',
          [uid, id, Date.now()],
        );
      }
    });
  } catch {
    // 写不进只影响重启后的已读,本次会话内存里已经生效
  }
}

function deleteReadIds(uid: string): void {
  if (db === null) return;
  try {
    db.runSync('DELETE FROM notification_read WHERE uid = ?', [uid]);
  } catch {
    // 同上
  }
}

interface NotificationsStore {
  /** 已读 ID 归属的账号;null = 游客态(不轮询、不展示) */
  activeUid: string | null;
  /** 合并后的条目,时间降序 */
  items: readonly NgaNotification[];
  readIds: ReadonlySet<string>;
  /** 手动/轮询刷新是否在途(页面空态转圈用) */
  refreshing: boolean;
  /**
   * 最近一次拉取抛出的错误**原样**;成功即清空。空列表配上它才知道是「没通知」还是
   * 「没拉到」。存对象不存 message:错误页要按 NgaError 的 kind 分文案(M4 验收缺陷 F4,
   * 压成字符串会让断网也落进 generic 兜底那句)。
   */
  error: unknown;
  /** 切号/登出:清条目、换已读桶 */
  activate: (uid: string | null) => void;
  /** 拉一次 get_all 并合并(只增不覆盖,core/local)。轮询与进页共用。 */
  refresh: () => Promise<void>;
  /** 标记已读并写盘 */
  markRead: (ids: readonly string[]) => void;
  /** 一键清空:服务端 del + 本地条目与已读全清 */
  clearAll: () => Promise<void>;
}

export const useNotifications = create<NotificationsStore>()((set, get) => ({
  activeUid: null,
  items: [],
  readIds: new Set<string>(),
  refreshing: false,
  error: null,

  activate: (uid) => {
    if (get().activeUid === uid) return;
    set({
      activeUid: uid,
      items: [],
      readIds: uid === null ? new Set<string>() : loadReadIds(uid),
      refreshing: false,
      error: null,
    });
  },

  refresh: async () => {
    const uid = get().activeUid;
    if (uid === null || get().refreshing) return;
    set({ refreshing: true });
    try {
      const feed = await fetchNotificationFeed(fetchNga);
      // 拉取期间可能切了号:结果归属旧账号就整个丢弃
      if (get().activeUid !== uid) return;
      set({ items: mergeNotifications(get().items, feed.items), error: null });
    } catch (cause) {
      // 轮询失败不打断谁:错误只记在 state 上,页面空着时才拿出来说
      if (get().activeUid !== uid) return;
      set({ error: cause ?? new Error('通知拉不下来') });
    } finally {
      if (get().activeUid === uid) set({ refreshing: false });
    }
  },

  markRead: (ids) => {
    const { activeUid, readIds } = get();
    if (activeUid === null) return;
    // 只把这次新读到的写盘:进页会把整屏条目都报一遍,老 ID 不必反复 INSERT
    const fresh = ids.filter((id) => !readIds.has(id));
    if (fresh.length === 0) return;
    set({ readIds: markRead(readIds, fresh) });
    persistReadIds(activeUid, fresh);
  },

  clearAll: async () => {
    const uid = get().activeUid;
    if (uid === null) return;
    await clearNotificationFeed(fetchNga);
    // 服务端清成功才动本地;切号期间回来的结果同样丢弃
    if (get().activeUid !== uid) return;
    set({ items: [], readIds: new Set<string>(), error: null });
    deleteReadIds(uid);
  },
}));

/** 抽屉角标用的未读数。游客态、以及关掉「被喷提示」时恒为 0。 */
export function useNotificationsUnread(): number {
  const enabled = useSettings((state) => state.settings.sprayNotice);
  const unread = useNotifications((state) => unreadCount(state.items, state.readIds));
  return enabled ? unread : 0;
}

/**
 * 前台轮询(spec §4)。挂在根布局,登录后才转:
 * - 只在 app 前台(AppState active)起定时器,退后台立即停;
 * - 一直拉空就退避(`POLL_BACKOFF_SKIPS`),回前台或拉到新通知就回到 60s;
 * - 切号/登出由 activate 重置状态并停掉旧账号的轮询;
 * - 关掉「被喷提示」(22 票)就整个不转——那一档要的正是「别再来打扰」。
 *   通知页自己进去还是会拉,那是用户主动看的。
 */
export function useNotificationsPoller(): void {
  const uid = useAccounts((state) => state.currentUid);
  const enabled = useSettings((state) => state.settings.sprayNotice);

  useEffect(() => {
    useNotifications.getState().activate(uid);
    if (uid === null || !enabled) return;

    let timer: ReturnType<typeof setInterval> | null = null;
    // 退避状态:连续拉空的次数,以及这一格之后还要跳过几格
    let idle = 0;
    let skip = 0;

    const tick = () => {
      if (skip > 0) {
        skip -= 1;
        return;
      }
      const before = useNotifications.getState().items.length;
      void useNotifications
        .getState()
        .refresh()
        .then(() => {
          // mergeNotifications 只增不覆盖,所以条数变多就是真拉到了新东西
          const fresh = useNotifications.getState().items.length > before;
          idle = fresh ? 0 : Math.min(idle + 1, POLL_BACKOFF_SKIPS.length - 1);
          skip = POLL_BACKOFF_SKIPS[idle] ?? 0;
        });
    };
    const start = () => {
      if (timer !== null) return;
      // 刚回到前台等于「用户现在就想看」:退避清零,先拉一发
      idle = 0;
      skip = 0;
      tick();
      timer = setInterval(tick, NOTIFICATION_POLL_MS);
    };
    const stop = () => {
      if (timer === null) return;
      clearInterval(timer);
      timer = null;
    };

    if (AppState.currentState === 'active') start();
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active') start();
      else stop();
    });
    return () => {
      stop();
      subscription.remove();
    };
  }, [uid, enabled]);
}
