import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 真实抓包样本（2026-08-07，bbs.nga.cn，curl 取得）。
 *
 * 与 core/net 的 fixture 同约定：存的是**原始响应字节**（GBK），不是 UTF-8 文本。
 * 分类树接口不需要登录，样本里没有任何账号信息；主题列表要登录，
 * 已脱敏：抓包账号的 uid（`__CU.uid`）替换成 10000001，cookie 不在响应体里。
 */

const fixturesDir = dirname(fileURLToPath(import.meta.url))

export interface ApiFixture {
  readonly contentType: string
  readonly file: string
  readonly note: string
}

export const API_FIXTURES = {
  /** app_api.php?__lib=home&__act=category —— 7 个分类、673 个版块 */
  homeCategory: {
    contentType: 'text/javascript; charset=GBK',
    file: 'home-category.gbk.bin',
    note: 'app_api.php __lib=home __act=category __output=8，游客身份',
  },
  /**
   * thread.php?fid=-7 —— 网事杂谈第一页。挑这个版块是因为一份样本里能同时看到
   * 匿名主题、子版块镜像行、彩色/加粗标题与各式 `parent`。
   * 服务端没声明 charset，body 是 GBK。
   */
  threadListLounge: {
    contentType: 'text/html',
    file: 'thread-list-fid-7.gbk.bin',
    note: 'thread.php fid=-7 page=1 __output=8，登录态',
  },
} as const satisfies Record<string, ApiFixture>

export type ApiFixtureName = keyof typeof API_FIXTURES

export function readFixtureBytes(name: ApiFixtureName): Uint8Array {
  return new Uint8Array(readFileSync(join(fixturesDir, API_FIXTURES[name].file)))
}

export function fixtureContentType(name: ApiFixtureName): string {
  return API_FIXTURES[name].contentType
}
