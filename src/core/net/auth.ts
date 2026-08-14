/**
 * 认证（API 文档 §0.2）。凭证是 WebView 登录后拿到的两个 Cookie：
 * `ngaPassportUid` → uid、`ngaPassportCid` → token。
 * 两种附加方式**等价，任选其一**：MNGA 走 POST form 字段，Android 走 Cookie 头。
 *
 * ⚠️ **Android 上不能只靠 `Cookie` 请求头**（2026-08-13 取证，「版块全空」排查）：
 * `expo/fetch` 用的是 RN 的 `OkHttpClientProvider` 单例，并在 `ExpoFetchModule.OnCreate`
 * 里挂了 `JavaNetCookieJar(ForwardingCookieHandler)`——也就是 WebView 的 `CookieManager`。
 * 反编译 okhttp-4.12.0 的 `BridgeInterceptor` 可见：`Host` / `Accept-Encoding` / `User-Agent`
 * 都有「请求里已经有了就不覆盖」的守卫，**唯独 `Cookie` 是无条件 `header()` 替换**——
 * 只要 jar 里对这个域名有任意一枚 cookie，我们手写的 `Cookie` 头就整条被顶掉。
 * 在 `bbs.nga.cn` 上通常无害（WebView 登录过，jar 里本来就有 passport cookie），
 * 但反封锁链一换到镜像域名（`ngabbs.com` 等），jar 里没有 passport cookie，
 * NGA 只要下发一枚自己的 cookie，**从第二发起请求就静默变成游客**。
 * 所以默认档位是 `both`：form 字段不经过 cookie jar，顶不掉。
 */

export interface NgaCredentials {
  readonly uid: string
  readonly token: string
}

export type AuthMode =
  /** body 里带 access_uid / access_token（MNGA 的做法） */
  | 'form'
  /** Cookie: ngaPassportUid=…; ngaPassportCid=…（Android 的做法） */
  | 'cookie'
  /** 两样都带（默认）。见文件头：Cookie 头在 Android 上会被 okhttp 的 cookie jar 顶掉 */
  | 'both'
  /** 游客访问 */
  | 'none'

export interface AuthAttachment {
  readonly headers: Readonly<Record<string, string>>
  readonly form: Readonly<Record<string, string>>
}

const EMPTY: AuthAttachment = { headers: {}, form: {} }

/** 把凭证按指定方式拼成待附加的 header 与 form 字段。无凭证时等同游客。 */
export function buildAuthAttachment(
  mode: AuthMode,
  credentials: NgaCredentials | null | undefined,
): AuthAttachment {
  if (mode === 'none' || !credentials) return EMPTY
  if (!credentials.uid || !credentials.token) return EMPTY

  const headers = {
    Cookie: `ngaPassportUid=${credentials.uid}; ngaPassportCid=${credentials.token}`,
  }
  const form = { access_uid: credentials.uid, access_token: credentials.token }

  if (mode === 'cookie') return { headers, form: {} }
  if (mode === 'form') return { headers: {}, form }
  return { headers, form }
}
