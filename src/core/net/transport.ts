/**
 * HTTP 传输层的最小接口。core/net 不直接依赖任何 RN/Expo API，
 * 设备侧注入 expo/fetch，单测注入假实现。
 */

export interface HttpRequest {
  readonly url: string
  readonly method: 'GET' | 'POST'
  readonly headers: Readonly<Record<string, string>>
  readonly body?: string
  readonly signal?: AbortSignal
}

export interface HttpResponse {
  readonly status: number
  readonly contentType: string | null
  /** 原始字节。响应可能是 GBK，所以不能让运行时按 UTF-8 直接给字符串 */
  readonly body: Uint8Array
}

export type HttpTransport = (request: HttpRequest) => Promise<HttpResponse>

type FetchLike = typeof globalThis.fetch

/**
 * 用标准 fetch 实现的传输层。
 *
 * ⚠️ 纪律（ADR-0002 / expo/expo#47762）：**禁止 clone/tee response**。
 * Expo SDK 57 的 Android 端 `expo/fetch` 在同一个 response 上读第二次会串流乱序，
 * 所以 body 一律**一次性**读完——这里只调一次 `arrayBuffer()`，之后不再碰 response.body。
 * （用 arrayBuffer 而不是 text 是因为 NGA 可能返回 GBK，必须自己解码，见 encoding/decode-body.ts。）
 */
export function createFetchTransport(fetchImpl?: FetchLike): HttpTransport {
  const doFetch: FetchLike = fetchImpl ?? globalThis.fetch
  return async (request) => {
    const response = await doFetch(request.url, {
      method: request.method,
      headers: { ...request.headers },
      body: request.body,
      signal: request.signal,
    })
    const buffer = await response.arrayBuffer()
    return {
      status: response.status,
      contentType: response.headers.get('content-type'),
      body: new Uint8Array(buffer),
    }
  }
}
