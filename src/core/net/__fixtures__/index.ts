import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 真实抓包样本（2026-08-07 / 08-08，bbs.nga.cn，用 .env.local 的测试账号 curl 取得）。
 *
 * 文件存的是**原始响应字节**（多数是 GBK），不是 UTF-8 文本——解码本身就是被测对象。
 * 已脱敏：抓包账号的 uid（出现在 `__CU` / 网页版的 `__CURRENT_UID` 里）统一替换成
 * 10000001，用户名替换成 `nga_user`；cookie / cid 不在响应体里，抓包时也没有落盘。
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
  /**
   * Web 反解档的样本（2026-08-08 抓取）：`read.php` **不带格式参数**拿到的整页 HTML。
   *
   * 这四份与 `core/api/__fixtures__` 里同 tid 的 `__output=8` 样本是同一批主题，
   * 反解结果可以直接跟 JSON 路线对拍。
   *
   * tid=46186286 第 1 页：匿名主楼（`authorid` 是 `-1`）、4 条热门回复、15 页分页。
   */
  readWebAnonymousHotReply: {
    contentType: 'text/html; charset=GBK',
    file: 'read-web-anonymous-hotreply.gbk.bin',
    note: 'read.php tid=46186286 page=1 v2=1，无格式参数（网页 HTML），登录态',
  },
  /** tid=44191387 第 1 页：第 4 楼带一条贴条（网页版嵌在 `comment_for_<pid>` 里）。 */
  readWebComment: {
    contentType: 'text/html; charset=GBK',
    file: 'read-web-comment.gbk.bin',
    note: 'read.php tid=44191387 page=1 v2=1，无格式参数（网页 HTML），登录态',
  },
  /** tid=47328470 第 1 页：主楼带两个附件（`ubbcode.attach.load`）与编辑记录（`loadAlertInfo`）。 */
  readWebAttachments: {
    contentType: 'text/html; charset=GBK',
    file: 'read-web-attachments.gbk.bin',
    note: 'read.php tid=47328470 page=1 v2=1，无格式参数（网页 HTML），登录态',
  },
  /** tid=1：网页版的错误页，错误码/文案夹在 `<!--msgcodestart-->` 一类注释标记里。 */
  readWebNotFound: {
    contentType: 'text/html; charset=GB18030',
    file: 'read-web-not-found.gbk.bin',
    note: 'read.php tid=1，无格式参数 → msgcode 2048 找不到主题',
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
