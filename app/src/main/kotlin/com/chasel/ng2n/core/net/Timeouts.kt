package com.chasel.ng2n.core.net

/**
 * 统一超时预算(**修审计 P2-06**)。
 *
 * ## RN 版的原行为
 *
 * `src/core/net/transport.ts:33-48` 只把调用方的 `AbortSignal` 透传下去,**没有任何 deadline**;
 * 真正的超时是 `expo/fetch` 底下那个 okhttp 单例的默认值(connect 10s / read 10s / call 0 =
 * 不限)。TanStack Query 的读取多半带取消信号,但 mutation 大多不带 —— 于是一发挂住的请求
 * 能把「格式轮换链 / UI loading / 写操作结果」一起吊在未知状态里,而且没有上限。
 *
 * ## 这一版
 *
 * connect / read(含 write)/ 整体 call 各一档,集中在这里,由
 * `data/net/OkHttpTransportFactory` 灌进 `OkHttpClient.Builder`。
 *
 * **取值理由**:NGA 正常响应 < 1.5s(perf-history 的冷启动拆解里,单次接口 RTT 约
 * 300–900ms);被封的响应是**立刻**回 403 / 一坨 HTML,不是挂起 —— 所以这几档只在
 * 「网络黑洞」(连上了但不吐字节)时才逼近。
 *
 * - [CONNECT_TIMEOUT_MS] = 8s:比 okhttp 默认的 10s 略紧。连不上就该早点换域名,
 *   反封锁链的下一档本来就是「换一个域名」,等满 10s 是纯浪费。
 * - [READ_TIMEOUT_MS] / [WRITE_TIMEOUT_MS] = 12s:两个 TCP 包之间的间隔上限,
 *   不是整篇响应的上限(一页 100 条主题的 GBK 响应约 200KB,弱网也不会两个包差 12s)。
 * - [CALL_TIMEOUT_MS] = 15s:**整体 deadline**,okhttp 的 `callTimeout` 覆盖 DNS、连接、
 *   重定向、读完 body 的全过程。一发最多 15s。
 *
 * ## 背靠背轮换行为不变
 *
 * 组合之间**没有退避**(照抄 RN):失败即刻发下一发。所以最坏情况是
 * `DEFAULT_MAX_ATTEMPTS`(8)× 15s = 120s —— 有上限,而 RN 版没有。真要更短得引入
 * 「整条链的 deadline」,那会改变轮换行为(轮到一半被掐),票面明确要求行为不变,不做。
 *
 * ## 写操作
 *
 * 超时的写操作**不自动重放**(P1-01):写请求的链只有 direct 一档,超时就是终点,
 * 由调用方决定要不要让用户重来。见 `NgaClient` 的 `writeChain`。
 */

/** 建立 TCP(含 DNS)的上限。 */
const val CONNECT_TIMEOUT_MS: Long = 8_000

/** 两次读到字节之间的上限。 */
const val READ_TIMEOUT_MS: Long = 12_000

/** 两次写出字节之间的上限。 */
const val WRITE_TIMEOUT_MS: Long = 12_000

/** 一次调用从发起到读完 body 的整体上限(okhttp 的 `callTimeout`)。 */
const val CALL_TIMEOUT_MS: Long = 15_000
