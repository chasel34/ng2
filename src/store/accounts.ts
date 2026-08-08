import * as SecureStore from 'expo-secure-store';
import { create } from 'zustand';

import {
  EMPTY_ACCOUNTS,
  addAccount,
  currentAccountOf,
  parseStoredAccounts,
  removeAccount,
  serializeAccounts,
  switchAccount,
  type AccountsState,
  type NgaAccount,
} from '@/core/account';
import type { NgaCredentials } from '@/core/net';

/** 换了存储结构就换 key,老数据自然作废,不用写迁移。 */
const STORE_KEY = 'accounts.v1';

/**
 * core/account 那套纯函数的落地:状态进 Zustand,每次变更同步写 SecureStore
 * (凭证是敏感数据,不进 MMKV,spec §3)。
 *
 * 用同步的 getItem/setItem 而不是 *Async:冷启动第一屏就要知道登录态,
 * 一个账号表也就几百字节,不值得为它引入异步水合的中间态。
 */
function loadInitial(): AccountsState {
  try {
    return parseStoredAccounts(SecureStore.getItem(STORE_KEY));
  } catch {
    // 拿不到(极端:keystore 坏掉/web 预览)按游客态起,登录后再写回
    return EMPTY_ACCOUNTS;
  }
}

function persist(state: AccountsState): void {
  try {
    SecureStore.setItem(STORE_KEY, serializeAccounts(state));
  } catch {
    // 写不进就只活在内存——功能还能用,重启后回游客态
  }
}

interface AccountsStore extends AccountsState {
  /** 登录成功落账号,并立即成为当前账号 */
  add: (account: NgaAccount) => void;
  /** 切换当前账号 */
  switchTo: (uid: string) => void;
  /** 退出某账号;退光即游客态 */
  logout: (uid: string) => void;
}

export const useAccounts = create<AccountsStore>()((set, get) => {
  const apply = (next: AccountsState) => {
    set(next);
    persist(next);
  };
  const snapshot = (): AccountsState => {
    const { accounts, currentUid } = get();
    return { accounts, currentUid };
  };
  return {
    ...loadInitial(),
    add: (account) => apply(addAccount(snapshot(), account)),
    switchTo: (uid) => apply(switchAccount(snapshot(), uid)),
    logout: (uid) => apply(removeAccount(snapshot(), uid)),
  };
});

/** 当前账号;游客态是 null。非 React 场合(fetcher)用它,组件里用 useAccounts。 */
export function currentAccount(): NgaAccount | null {
  return currentAccountOf(useAccounts.getState());
}

/**
 * 给 core/net fetcher 的凭证读取口(每次请求现取,切换账号后下一个请求即用新 cookie)。
 * core 层零 RN 依赖,SecureStore 只在这一层出现——core 拿到的只是纯数据。
 */
export function currentCredentials(): NgaCredentials | null {
  const account = currentAccount();
  return account === null ? null : { uid: account.uid, token: account.cid };
}
