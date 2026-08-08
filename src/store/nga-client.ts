import { fetch as expoFetch } from 'expo/fetch';

import { createFetchTransport, createNgaFetcher, type NgaFetcher } from '@/core/net';

import { currentCredentials } from './accounts';

/**
 * 全 app 共用的 NGA 请求器——core/net 的策略链在这里接上设备侧的 HTTP 实现。
 *
 * 用 `expo/fetch` 而不是全局 fetch:它是 New Arch 上的原生实现,
 * 流式读取与 AbortSignal 行为可控(纪律见 core/net/transport.ts:禁止 clone response)。
 *
 * 凭证按请求现取(而不是建 fetcher 时定死):切换/退出账号后,
 * 下一个请求自动带新账号 cookie;游客态返回 null,请求不带凭证。
 */
export const fetchNga: NgaFetcher = createNgaFetcher({
  transport: createFetchTransport(expoFetch as unknown as typeof globalThis.fetch),
  getCredentials: currentCredentials,
});
