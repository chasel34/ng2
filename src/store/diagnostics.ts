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

export function recordFetchDiagnostic(diagnostic: FetchDiagnostic): void {
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
