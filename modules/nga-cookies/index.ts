import { requireNativeModule } from 'expo';

/** 见 NgaCookiesModule.kt——读 Android WebView 原生 cookie 仓库(含 HttpOnly)。 */
type NgaCookiesModule = {
  /** 返回 `k=v; k2=v2` 原始 cookie 串,没有则空串。 */
  getCookieString(url: string): Promise<string>;
  /** 清空 WebView 全部 cookie,返回是否真的清掉了什么。 */
  clearAll(): Promise<boolean>;
};

export default requireNativeModule<NgaCookiesModule>('NgaCookies');
