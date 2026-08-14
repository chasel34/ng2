import { fetch as expoFetch } from 'expo/fetch';

import {
  createComboCache,
  createFetchTransport,
  createFormatRotationStrategy,
  createNgaFetcher,
  createSwitchAccountStrategy,
  createTopicCacheStrategy,
  createWebFallbackStrategy,
  type NgaFetcher,
} from '@/core/net';

import { allCredentials, currentCredentials } from './accounts';
import { recordFetchDiagnostic } from './diagnostics';
import { readPhpUserAgent, webFallbackMode } from './net-settings';
import { currentHost } from './settings';
import { readCachedPage } from './topic-cache';

/**
 * 全 app 共用的 NGA 请求器——core/net 的策略链在这里接上设备侧的 HTTP 实现。
 *
 * 用 `expo/fetch` 而不是全局 fetch:它是 New Arch 上的原生实现,
 * 流式读取与 AbortSignal 行为可控(纪律见 core/net/transport.ts:禁止 clone response)。
 *
 * 凭证按请求现取(而不是建 fetcher 时定死):切换/退出账号后,
 * 下一个请求自动带新账号 cookie;游客态返回 null,请求不带凭证。
 *
 * 链的顺序即 ADR-0002 的顺序,现在落地了前三档:
 *
 * 1. `format-rotation` 格式参数 × 域名的组合枚举,成功组合按接口 key 缓存
 * 2. `switch-account`  换下一个已登录账号的 cookie 试一次(仅多账号)
 * 3. `web-fallback`    read.php 专用:拿网页版 HTML 反解出同构信封(19 票)
 * 4. `topic-cache`     read.php 专用:从本机 SQLite 还原上次存下的那一页(20 票)
 * 5. 网页兜底页(19 票,不在链上——是链失败后的一个路由)
 *
 * `web-fallback` 在链上出现两次是刻意的:档位(Disabled/Secondary/Primary/Only)是用户
 * 设置,而链的顺序建 fetcher 时就定死了,只能两个位置各摆一条、各自按档位决定跑不跑。
 */
const createTransport = () =>
  createFetchTransport(expoFetch as unknown as typeof globalThis.fetch);

/**
 * 成功组合缓存。建在外面(而不是让 fetcher 自己建)是为了让 UI 能清它:
 * 详情页的「重试原生」要从头试探,不能又从上次那个已经不灵的组合开局。
 */
const comboCache = createComboCache();

/**
 * 忘掉某个接口上次试通的格式 × 域名组合(ADR-0002)。
 * key 即接口 key(`read.php`、`thread.php`、`nuke.php?__lib=…&__act=…`)。
 *
 * ⚠️ `thread.php` 这一条是**版块列表 / 搜索 / 收藏夹 / 热帖 / 精华区 / 某人的主题共用**的
 * (接口 key 的粒度就是脚本 + `__lib/__act`),清它等于让这半个 app 一起重新试探。
 */
export function forgetSuccessfulCombo(interfaceKey: string): void {
  comboCache.forget(interfaceKey);
}

/**
 * 本次运行里各接口当前挂在哪个组合上(实验室页的「本次运行的组合」)。
 *
 * 有它才看得出「所有版块都空」是不是因为 `thread.php` 被钉在了某个坏组合上——
 * 那次事故里这件事在界面上完全不可见(2026-08-13 排查)。
 */
export function successfulCombos(): readonly { key: string; combo: string; at: number }[] {
  return comboCache.entries().map(([key, record]) => ({
    key,
    combo: `${record.combo.format} @ ${record.combo.host}`,
    at: record.at,
  }));
}

export const fetchNga: NgaFetcher = createNgaFetcher({
  // 传工厂而不是实例:反封锁链每次重试前要重建 HTTP client(ADR-0002)
  createTransport,
  comboCache,
  getCredentials: currentCredentials,
  // 域名同样按请求现取(22 票的设置项):设置页换完,下一个请求就发到新域名。
  // 格式轮换那一档会把它排在官方域名表最前面,被封了照样往后换
  getHost: currentHost,
  getReadPhpUserAgent: readPhpUserAgent,
  strategies: [
    createWebFallbackStrategy({ placement: 'primary', getMode: webFallbackMode }),
    createFormatRotationStrategy(),
    createSwitchAccountStrategy({ listCredentials: allCredentials }),
    createWebFallbackStrategy({ placement: 'secondary', getMode: webFallbackMode }),
    // 链的最后一档:网络这条路已经走完了,还剩本机存着的那一页(20 票)。
    // 读是同步的 SQLite 查询,命中不了就报 unavailable 让错误页/网页兜底接手
    createTopicCacheStrategy({ store: { read: readCachedPage } }),
  ],
  onDiagnostic: recordFetchDiagnostic,
});
