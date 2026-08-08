import { fetch as expoFetch } from 'expo/fetch';

import {
  createFetchTransport,
  createFormatRotationStrategy,
  createNgaFetcher,
  createSwitchAccountStrategy,
  type NgaFetcher,
} from '@/core/net';

import { allCredentials, currentCredentials } from './accounts';
import { recordFetchDiagnostic } from './diagnostics';
import { readPhpUserAgent } from './net-settings';

/**
 * 全 app 共用的 NGA 请求器——core/net 的策略链在这里接上设备侧的 HTTP 实现。
 *
 * 用 `expo/fetch` 而不是全局 fetch:它是 New Arch 上的原生实现,
 * 流式读取与 AbortSignal 行为可控(纪律见 core/net/transport.ts:禁止 clone response)。
 *
 * 凭证按请求现取(而不是建 fetcher 时定死):切换/退出账号后,
 * 下一个请求自动带新账号 cookie;游客态返回 null,请求不带凭证。
 *
 * 链的顺序即 ADR-0002 的顺序,现在落地了前两档:
 *
 * 1. `format-rotation` 格式参数 × 域名的组合枚举,成功组合按接口 key 缓存
 * 2. `switch-account`  换下一个已登录账号的 cookie 试一次(仅多账号)
 * 3. Web 反解(19 号票)、4. 帖子缓存(20 号票)、5. 网页兜底(19 号票)按序追加即可
 */
const createTransport = () =>
  createFetchTransport(expoFetch as unknown as typeof globalThis.fetch);

export const fetchNga: NgaFetcher = createNgaFetcher({
  // 传工厂而不是实例:反封锁链每次重试前要重建 HTTP client(ADR-0002)
  createTransport,
  getCredentials: currentCredentials,
  getReadPhpUserAgent: readPhpUserAgent,
  strategies: [
    createFormatRotationStrategy(),
    createSwitchAccountStrategy({ listCredentials: allCredentials }),
  ],
  onDiagnostic: recordFetchDiagnostic,
});
