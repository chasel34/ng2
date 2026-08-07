import { fetch as expoFetch } from 'expo/fetch';

import { createFetchTransport, createNgaFetcher, type NgaFetcher } from '@/core/net';

/**
 * 全 app 共用的 NGA 请求器——core/net 的策略链在这里接上设备侧的 HTTP 实现。
 *
 * 用 `expo/fetch` 而不是全局 fetch:它是 New Arch 上的原生实现,
 * 流式读取与 AbortSignal 行为可控(纪律见 core/net/transport.ts:禁止 clone response)。
 *
 * 账号凭证要等 09(WebView 登录 + 多账号)接进来:届时给 `getCredentials`
 * 传当前账号即可,分类树这类游客也能拿的接口不受影响。
 */
export const fetchNga: NgaFetcher = createNgaFetcher({
  transport: createFetchTransport(expoFetch as unknown as typeof globalThis.fetch),
});
