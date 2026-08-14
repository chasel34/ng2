import { appendDiagnosticLog, type FetchDiagnostic } from '@/core/net';

import { storage } from './storage';

/** 换了存储结构就换 key,老日志自然作废,不用写迁移。 */
const STORE_KEY = 'diagnostics.log.v1';

/**
 * 反封锁链的诊断日志(ADR-0002 / 18 号票)。
 *
 * 全链失败时把「试过哪些格式 × 域名组合、每一档为什么败」落到 MMKV,
 * 供 22 号票的「导出诊断日志」消费,错误页也从抛出的错误上取同一份数据显示摘要。
 *
 * 存 JSON 数组而不是拼一个大字符串:一条记录本身是多行的,拼起来就没法按条裁了。
 * 日志里只有 uid,没有 cid——凭证不许出现在能导出的文件里。
 */
function read(): readonly string[] {
  try {
    const raw = storage.getString(STORE_KEY);
    if (raw === undefined || raw === '') return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((item) => typeof item === 'string') : [];
  } catch {
    // 日志本身坏了不该拖垮请求链,当空的重新攒
    return [];
  }
}

/**
 * 本次运行的请求落点表(H2,2026-08-13「版块全空」排查)。
 *
 * **只活在内存里**,进程一死就没了——它要回答的正是「这个进程现在挂在哪个组合上」。
 * 成功的请求也进这张表:那次事故里整条链自认为成功,失败日志里一条记录都没有,
 * 从界面上完全看不出「我们其实没拿到列表」。
 *
 * 不落 MMKV 是有意的:每次请求都读一遍整份日志再 stringify 写回去,是个
 * O(日志长度) 的同步写,请求热路径上不该有这个(M4 性能走查还在跑)。
 */
export interface RunLogEntry {
  readonly at: number;
  readonly path: string;
  readonly params: Readonly<Record<string, string>>;
  /** 成功时是落点摘要(组合 + data 顶层键 + 条数),失败时是最终错误 */
  readonly message: string;
  readonly ok: boolean;
  /** 这一次链上真发出去了几次 HTTP */
  readonly attempts: number;
}

const RUN_LOG_LIMIT = 40;
let runLog: RunLogEntry[] = [];

/** 最新的在前。实验室页的「本次运行的请求」拿它渲染。 */
export function readRunLog(): readonly RunLogEntry[] {
  return runLog;
}

export function clearRunLog(): void {
  runLog = [];
}

/**
 * 成功的请求要不要也落盘。
 *
 * 全落会把导出日志冲垮(一屏能几十条),所以只留**值得看的那些**:
 * 组合换了(反封锁链真的动了)、或者试了不止一次。稳态下的成功一条都不写。
 */
const lastPersistedCombo = new Map<string, string>();

function worthPersisting(diagnostic: FetchDiagnostic): boolean {
  if (diagnostic.success === undefined) return true; // 失败一律留
  const combo = `${diagnostic.success.format} @ ${diagnostic.success.host}`;
  const previous = lastPersistedCombo.get(diagnostic.path);
  // 先记上再判：早退会让水位线停在旧值,下一条同组合的成功又被当成「换组合了」
  lastPersistedCombo.set(diagnostic.path, combo);
  return diagnostic.attempts.length > 1 || (previous !== undefined && previous !== combo);
}

export function recordFetchDiagnostic(diagnostic: FetchDiagnostic): void {
  runLog = [
    {
      at: diagnostic.at,
      path: diagnostic.path,
      params: diagnostic.params,
      message: diagnostic.message,
      ok: diagnostic.success !== undefined,
      attempts: diagnostic.attempts.length,
    },
    ...runLog,
  ].slice(0, RUN_LOG_LIMIT);

  if (!worthPersisting(diagnostic)) return;
  try {
    storage.set(STORE_KEY, JSON.stringify(appendDiagnosticLog(read(), diagnostic)));
  } catch {
    // 写不进就算了:这是排障用的旁路,不能影响主流程
  }
}

/** 最新的在最后。22 号票的「导出诊断日志」拿它拼文件。 */
export function readDiagnosticLog(): readonly string[] {
  return read();
}

export function clearDiagnosticLog(): void {
  try {
    storage.remove(STORE_KEY);
  } catch {
    // 同上
  }
}
