/**
 * 多账号模型（CONTEXT.md「账号」：可同时登录多个，有且仅有一个当前账号）。
 * 纯函数集合，SecureStore 落盘与 Zustand 接线在 store 层（core 零 RN 依赖）。
 */

export interface NgaAccount {
  readonly uid: string
  /** 会话凭证（Cookie ngaPassportCid） */
  readonly cid: string
  /** 展示名；用户名 cookie 抓不到时回落为 `UID <uid>` */
  readonly name: string
  /** 登录时刻（ms since epoch），cookie 过期天数按它推算 */
  readonly loginAt: number
}

export interface AccountsState {
  readonly accounts: readonly NgaAccount[]
  /** 当前账号 uid；null 即游客态 */
  readonly currentUid: string | null
}

export const EMPTY_ACCOUNTS: AccountsState = { accounts: [], currentUid: null }

/**
 * NGA passport cookie 的有效期。Set-Cookie 的真实 expires 拿不到
 * （document.cookie 不暴露属性），按 passport 的 30 天惯例从登录时刻本地推算，
 * 只作账号管理页的展示（设计稿「cookie 30 天后过期」）。
 */
export const COOKIE_LIFETIME_DAYS = 30

const DAY_MS = 24 * 60 * 60 * 1000

/** 距 cookie 过期还剩几天（向上取整；刚登录=30，已过期≤0）。 */
export function cookieDaysLeft(loginAt: number, now: number): number {
  return Math.ceil((loginAt + COOKIE_LIFETIME_DAYS * DAY_MS - now) / DAY_MS)
}

/** 账号管理页的过期文案。 */
export function formatCookieExpiry(loginAt: number, now: number): string {
  const days = cookieDaysLeft(loginAt, now)
  return days > 0 ? `${days} 天后过期` : '已过期'
}

export function currentAccountOf(state: AccountsState): NgaAccount | null {
  return state.accounts.find((account) => account.uid === state.currentUid) ?? null
}

/**
 * 登录成功后落账号：同 uid 重登就地刷新（新 cid、新 loginAt，位置不动），
 * 新账号追加到末尾；登录进来的账号立刻成为当前账号。
 */
export function addAccount(state: AccountsState, account: NgaAccount): AccountsState {
  const exists = state.accounts.some((item) => item.uid === account.uid)
  const accounts = exists
    ? state.accounts.map((item) => (item.uid === account.uid ? account : item))
    : [...state.accounts, account]
  return { accounts, currentUid: account.uid }
}

/** 切换当前账号；uid 不在列表里就原样返回（防御过期的 UI 事件）。 */
export function switchAccount(state: AccountsState, uid: string): AccountsState {
  if (!state.accounts.some((account) => account.uid === uid)) return state
  return { accounts: state.accounts, currentUid: uid }
}

/** 退出某账号。退的是当前账号时落到剩余第一个；全退光即游客态。 */
export function removeAccount(state: AccountsState, uid: string): AccountsState {
  const accounts = state.accounts.filter((account) => account.uid !== uid)
  const currentUid =
    state.currentUid === uid ? (accounts[0]?.uid ?? null) : state.currentUid
  return { accounts, currentUid }
}

/**
 * 抽屉头部左右滑动的「下一个/上一个」账号 uid（循环取）；
 * 不足两个账号没得切，返回 null。
 */
export function cycleAccountUid(state: AccountsState, step: 1 | -1): string | null {
  const { accounts, currentUid } = state
  if (accounts.length < 2) return null
  const index = accounts.findIndex((account) => account.uid === currentUid)
  const next = (index + step + accounts.length) % accounts.length
  return accounts[next]!.uid
}

/** 序列化成 SecureStore 里存的那份 JSON。 */
export function serializeAccounts(state: AccountsState): string {
  return JSON.stringify(state)
}

function isValidAccount(value: unknown): value is NgaAccount {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return (
    typeof record['uid'] === 'string' &&
    record['uid'] !== '' &&
    typeof record['cid'] === 'string' &&
    record['cid'] !== '' &&
    typeof record['name'] === 'string' &&
    typeof record['loginAt'] === 'number' &&
    Number.isFinite(record['loginAt'])
  )
}

/**
 * 反序列化 SecureStore 里的账号表。这份 JSON 可能出自旧版本 app，
 * 一律当外部输入校验：坏账号剔除、currentUid 不在表里就落到第一个、
 * 整串坏掉退回空表（等价游客态），绝不抛。
 */
export function parseStoredAccounts(raw: string | null): AccountsState {
  if (raw === null || raw === '') return EMPTY_ACCOUNTS
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return EMPTY_ACCOUNTS
    const record = parsed as Record<string, unknown>
    const rawAccounts = Array.isArray(record['accounts']) ? record['accounts'] : []
    const seen = new Set<string>()
    const accounts: NgaAccount[] = []
    for (const item of rawAccounts) {
      if (!isValidAccount(item) || seen.has(item.uid)) continue
      seen.add(item.uid)
      accounts.push({ uid: item.uid, cid: item.cid, name: item.name, loginAt: item.loginAt })
    }
    const storedUid = typeof record['currentUid'] === 'string' ? record['currentUid'] : null
    const currentUid =
      storedUid !== null && seen.has(storedUid) ? storedUid : (accounts[0]?.uid ?? null)
    return { accounts, currentUid }
  } catch {
    return EMPTY_ACCOUNTS
  }
}
