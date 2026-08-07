/**
 * 认证（API 文档 §0.2）。凭证是 WebView 登录后拿到的两个 Cookie：
 * `ngaPassportUid` → uid、`ngaPassportCid` → token。
 * 两种附加方式**等价，任选其一**：MNGA 走 POST form 字段，Android 走 Cookie 头。
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

  if (mode === 'cookie') {
    return {
      headers: {
        Cookie: `ngaPassportUid=${credentials.uid}; ngaPassportCid=${credentials.token}`,
      },
      form: {},
    }
  }
  return {
    headers: {},
    form: { access_uid: credentials.uid, access_token: credentials.token },
  }
}
