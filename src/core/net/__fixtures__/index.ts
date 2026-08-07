import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 真实抓包样本（2026-08-07，bbs.nga.cn，用 .env.local 的测试账号 curl 取得）。
 *
 * 文件存的是**原始响应字节**（多数是 GBK），不是 UTF-8 文本——解码本身就是被测对象。
 * 已脱敏：抓包账号的 uid（出现在 `__CU` 里）统一替换成 10000001；
 * cookie / cid 不在响应体里，抓包时也没有落盘。
 */

const fixturesDir = dirname(fileURLToPath(import.meta.url))

export interface NetFixture {
  /** 抓包时服务端实际声明的 Content-Type，原样保留（注意 thread.php 那条没声明 charset） */
  readonly contentType: string
  readonly file: string
  readonly note: string
}

export const NET_FIXTURES = {
  /** nuke.php?__lib=noti&__act=get_all —— 没有新通知时的空 data */
  notiEmpty: {
    contentType: 'text/javascript; charset=GBK',
    file: 'noti-empty.gbk.bin',
    note: 'nuke.php __lib=noti __act=get_all，登录态，无未读',
  },
  /** thread.php?fid=650 —— 唯一一条服务端没声明 charset 的，body 是 GBK */
  threadList: {
    contentType: 'text/html',
    file: 'thread-list-fid650.gbk.bin',
    note: 'thread.php fid=650 page=1 __output=8；Content-Type 没带 charset，必须靠回落解码',
  },
  /** read.php?lite=js —— 带 window.script_muti_get_var_store= 前缀 */
  readThread: {
    contentType: 'text/javascript; charset=GBK',
    file: 'read-thread-45150945.jsvar.gbk.bin',
    note: 'read.php tid=45150945 page=1 lite=js；带 JS 变量前缀',
  },
  /** nuke.php?__lib=ucp&__act=get —— 正常用户资料 */
  ucpUser: {
    contentType: 'text/javascript; charset=GBK',
    file: 'ucp-user-41417929.gbk.bin',
    note: 'nuke.php __lib=ucp __act=get uid=41417929',
  },
  /** 假错误：找不到用户（白名单里，要当成功） */
  ucpNotFound: {
    contentType: 'text/javascript; charset=GBK',
    file: 'ucp-not-found.gbk.bin',
    note: '不存在的 uid → {"error":{"0":"找不到用户"}}，命中假错误白名单',
  },
  /** 真错误：找不到主题 */
  readThreadNotFound: {
    contentType: 'text/javascript; charset=GBK',
    file: 'read-thread-not-found.gbk.bin',
    note: 'read.php tid=1 → {"error":{"0":"2048:找不到主题",…}}，真错误',
  },
} as const satisfies Record<string, NetFixture>

export type NetFixtureName = keyof typeof NET_FIXTURES

/** 读原始响应字节。 */
export function readFixtureBytes(name: NetFixtureName): Uint8Array {
  return new Uint8Array(readFileSync(join(fixturesDir, NET_FIXTURES[name].file)))
}

/** 抓包时的 Content-Type。 */
export function fixtureContentType(name: NetFixtureName): string {
  return NET_FIXTURES[name].contentType
}
