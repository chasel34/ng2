import { describe, expect, it } from 'vitest'
import {
  EMPTY_ACCOUNTS,
  addAccount,
  cookieDaysLeft,
  currentAccountOf,
  cycleAccountUid,
  formatCookieExpiry,
  parseStoredAccounts,
  removeAccount,
  serializeAccounts,
  switchAccount,
  type AccountsState,
  type NgaAccount,
} from './accounts'

function account(uid: string, overrides: Partial<NgaAccount> = {}): NgaAccount {
  return { uid, cid: `cid-${uid}`, name: `用户${uid}`, loginAt: 1_754_000_000_000, ...overrides }
}

const TWO: AccountsState = {
  accounts: [account('1001'), account('1002')],
  currentUid: '1001',
}

describe('addAccount', () => {
  it('新账号追加到末尾并立即成为当前账号', () => {
    const state = addAccount(EMPTY_ACCOUNTS, account('1001'))
    expect(state.accounts.map((a) => a.uid)).toEqual(['1001'])
    expect(state.currentUid).toBe('1001')

    const next = addAccount(state, account('1002'))
    expect(next.accounts.map((a) => a.uid)).toEqual(['1001', '1002'])
    expect(next.currentUid).toBe('1002')
  })

  it('同 uid 重登就地刷新 cid 与 loginAt,位置不动', () => {
    const relogin = account('1001', { cid: 'cid-new', loginAt: 1_754_100_000_000 })
    const state = addAccount(TWO, relogin)
    expect(state.accounts.map((a) => a.uid)).toEqual(['1001', '1002'])
    expect(state.accounts[0]).toEqual(relogin)
    expect(state.currentUid).toBe('1001')
  })
})

describe('switchAccount / removeAccount', () => {
  it('切换只动 currentUid;uid 不在表里原样返回', () => {
    expect(switchAccount(TWO, '1002').currentUid).toBe('1002')
    expect(switchAccount(TWO, '9999')).toBe(TWO)
  })

  it('退出当前账号落到剩余第一个;全退光即游客态(currentUid=null)', () => {
    const one = removeAccount(TWO, '1001')
    expect(one.accounts.map((a) => a.uid)).toEqual(['1002'])
    expect(one.currentUid).toBe('1002')

    const none = removeAccount(one, '1002')
    expect(none).toEqual(EMPTY_ACCOUNTS)
  })

  it('退出非当前账号不影响当前账号', () => {
    const state = removeAccount(TWO, '1002')
    expect(state.currentUid).toBe('1001')
  })

  it('currentAccountOf 取当前账号;游客态是 null', () => {
    expect(currentAccountOf(TWO)?.uid).toBe('1001')
    expect(currentAccountOf(EMPTY_ACCOUNTS)).toBeNull()
  })
})

describe('cycleAccountUid · 抽屉左右滑动切换', () => {
  const three: AccountsState = {
    accounts: [account('1001'), account('1002'), account('1003')],
    currentUid: '1002',
  }

  it('前后循环取,到头绕回', () => {
    expect(cycleAccountUid(three, 1)).toBe('1003')
    expect(cycleAccountUid(three, -1)).toBe('1001')
    expect(cycleAccountUid({ ...three, currentUid: '1003' }, 1)).toBe('1001')
    expect(cycleAccountUid({ ...three, currentUid: '1001' }, -1)).toBe('1003')
  })

  it('不足两个账号没得切', () => {
    expect(cycleAccountUid(EMPTY_ACCOUNTS, 1)).toBeNull()
    expect(cycleAccountUid({ accounts: [account('1001')], currentUid: '1001' }, 1)).toBeNull()
  })
})

describe('cookie 过期天数(30 天惯例,本地推算)', () => {
  const DAY = 24 * 60 * 60 * 1000
  const loginAt = 1_754_000_000_000

  it('刚登录 30 天,过一天半剩 29(向上取整)', () => {
    expect(cookieDaysLeft(loginAt, loginAt)).toBe(30)
    expect(cookieDaysLeft(loginAt, loginAt + 1.5 * DAY)).toBe(29)
    expect(cookieDaysLeft(loginAt, loginAt + 29.5 * DAY)).toBe(1)
  })

  it('过了 30 天算已过期', () => {
    expect(cookieDaysLeft(loginAt, loginAt + 31 * DAY)).toBeLessThanOrEqual(0)
    expect(formatCookieExpiry(loginAt, loginAt + 31 * DAY)).toBe('已过期')
    expect(formatCookieExpiry(loginAt, loginAt)).toBe('30 天后过期')
  })
})

describe('SecureStore 序列化往返与容错', () => {
  it('serialize → parse 往返一致', () => {
    expect(parseStoredAccounts(serializeAccounts(TWO))).toEqual(TWO)
  })

  it('空值与坏 JSON 退回空表(游客态),绝不抛', () => {
    expect(parseStoredAccounts(null)).toEqual(EMPTY_ACCOUNTS)
    expect(parseStoredAccounts('')).toEqual(EMPTY_ACCOUNTS)
    expect(parseStoredAccounts('not json')).toEqual(EMPTY_ACCOUNTS)
    expect(parseStoredAccounts('[]')).toEqual(EMPTY_ACCOUNTS)
  })

  it('坏账号剔除、重复 uid 去重、currentUid 失效时落到第一个', () => {
    const raw = JSON.stringify({
      accounts: [
        account('1001'),
        { uid: '1002' }, // 缺字段
        account('1001', { cid: 'dup' }), // 重复 uid
        account('1003'),
      ],
      currentUid: '9999',
    })
    const state = parseStoredAccounts(raw)
    expect(state.accounts.map((a) => a.uid)).toEqual(['1001', '1003'])
    expect(state.accounts[0]?.cid).toBe('cid-1001')
    expect(state.currentUid).toBe('1001')
  })
})
