import { create } from 'zustand';

import { checkIn, type CheckInResult } from '@/core/api';
import {
  EMPTY_CHECK_IN_DAYS,
  isCheckedInOn,
  markCheckedIn,
  parseCheckInDays,
  serializeCheckInDays,
  type CheckInDays,
} from '@/core/local';

import { fetchNga } from './nga-client';
import { storage } from './storage';

/** 换了存储结构就换 key,老数据自然作废。 */
const STORE_KEY = 'check-in.days.v1';

/**
 * 每日签到(CONTEXT.md「签到」)的设备侧落地。
 *
 * core/local 那套按 UTC+8 记账的纯函数进 Zustand,每次变更写 MMKV
 * (小数据,不进 sqlite——spec §3)。签到日按账号分桶,切号后各签各的。
 *
 * **今天签过就不发请求**:服务端没有「查今天签没签」的接口,重复签到只会回一句
 * 假错误,白打一次接口(ADR-0002:能少打就少打)。在途的那次也挡住重复点击。
 */
function load(): CheckInDays {
  try {
    return parseCheckInDays(storage.getString(STORE_KEY));
  } catch {
    // 读不到就当没签过:最坏是今天多发一次请求,服务端本来就幂等
    return EMPTY_CHECK_IN_DAYS;
  }
}

function persist(days: CheckInDays): void {
  try {
    storage.set(STORE_KEY, serializeCheckInDays(days));
  } catch {
    // 写不进就只活在内存,重启后至多多签一次
  }
}

/** 一次点击的结果。真失败(未登录、被封)走 reject,不在这里表达。 */
export type CheckInOutcome =
  /** 本地记录显示今天已经签过,压根没发请求 */
  | { kind: 'already-today' }
  /** 上一次还在途,这次点击被忽略 */
  | { kind: 'in-flight' }
  /** 发了请求且成功;`result.alreadyCheckedIn` 表示服务端说今天已签过 */
  | { kind: 'checked-in'; result: CheckInResult };

interface CheckInStore {
  /** uid → 最后签到日(UTC+8) */
  days: CheckInDays;
  /** 正在签的账号 uid;null = 没有在途请求 */
  pendingUid: string | null;
  checkIn: (uid: string) => Promise<CheckInOutcome>;
}

export const useCheckIn = create<CheckInStore>()((set, get) => ({
  days: load(),
  pendingUid: null,

  checkIn: async (uid) => {
    const now = Date.now();
    if (isCheckedInOn(get().days, uid, now)) return { kind: 'already-today' };
    if (get().pendingUid !== null) return { kind: 'in-flight' };

    set({ pendingUid: uid });
    try {
      const result = await checkIn(fetchNga);
      // 服务端说「今天已经签到」也记上:今天剩下的时间不必再问了
      const days = markCheckedIn(get().days, uid, Date.now());
      set({ days });
      persist(days);
      return { kind: 'checked-in', result };
    } finally {
      set({ pendingUid: null });
    }
  },
}));

/** 这个账号今天(UTC+8)签过了吗。游客态恒为 false。 */
export function useCheckedInToday(uid: string | null): boolean {
  const days = useCheckIn((state) => state.days);
  return uid !== null && isCheckedInOn(days, uid, Date.now());
}
