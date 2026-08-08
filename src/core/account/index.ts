/**
 * core/account —— 登录凭证识别与多账号模型。
 * 纯 TS，零 RN 依赖；SecureStore 落盘与请求注入在 store 层接线。
 */

export {
  COOKIE_LIFETIME_DAYS,
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
export { extractLoginCookies, parseCookieString, type LoginCookies } from './login-cookies'
export { decodeLoginUsername } from './username'
