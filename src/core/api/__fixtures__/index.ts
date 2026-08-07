import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 真实抓包样本（2026-08-07 / 08-08，bbs.nga.cn，curl 取得）。
 *
 * 与 core/net 的 fixture 同约定：存的是**原始响应字节**（GBK），不是 UTF-8 文本。
 * 分类树接口不需要登录，样本里没有任何账号信息；主题列表与帖子详情要登录，
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
  /**
   * read.php tid=46186286 —— 匿名主楼。`__R[0].authorid` 是 `-1`，用户表里同时有
   * `-1`/`-2` 两个匿名槽位（都指向同一个 `#anony_` 串），是「匿名 id 要加请求级前缀」
   * 那条规则的样本；另有 `hotreply`（热门回复，只挂在主楼）、`__GROUPS` 等级名、
   * `buffs` 用户状态与 `__F.custom_level`。
   */
  readAnonymousHotReply: {
    contentType: 'text/javascript; charset=GBK',
    file: 'read-anonymous-hotreply.gbk.bin',
    note: 'read.php tid=46186286 page=1 __output=8 v2=1，登录态',
  },
  /**
   * read.php tid=44191387 —— 有贴条的一页。贴条既挂在被贴楼层的 `comment` 下，
   * **又在 `__R` 里占一条只有 `subject`/`comment_to_id`、没有 `content` 的幽灵行**，
   * 楼层流必须把幽灵行滤掉。同一页还有 `[noimg]./xxx[/noimg]` 相对路径图片
   * 与 `attachs:""`（附件字段是空串而不是对象）。
   */
  readComment: {
    contentType: 'text/javascript; charset=GBK',
    file: 'read-comment-noimg.gbk.bin',
    note: 'read.php tid=44191387 page=1 __output=8 v2=1，登录态',
  },
  /** read.php tid=47328470 —— 主楼带两个 `attachs` 附件（`thumb` 非空）+ 热门回复。 */
  readAttachments: {
    contentType: 'text/javascript; charset=GBK',
    file: 'read-attachments.gbk.bin',
    note: 'read.php tid=47328470 page=1 __output=8 v2=1，登录态',
  },
} as const satisfies Record<string, ApiFixture>

export type ApiFixtureName = keyof typeof API_FIXTURES

export function readFixtureBytes(name: ApiFixtureName): Uint8Array {
  return new Uint8Array(readFileSync(join(fixturesDir, API_FIXTURES[name].file)))
}

export function fixtureContentType(name: ApiFixtureName): string {
  return API_FIXTURES[name].contentType
}
