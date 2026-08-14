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
  /**
   * forum_favor2 action=get —— 收藏了 1 个合集（stid=31576766，条目只带 `stid` 不带 `fid`）
   * 与 1 个负 fid 版块（fid=-7 网事杂谈）之后的列表，**新收藏的排在前面**。
   * 版块数组挂在 `data["0"]` 下（API 文档 §1.3）；响应体里没有任何账号信息。
   */
  forumFavorList: {
    contentType: 'text/javascript; charset=GBK',
    file: 'forum-favor-get.gbk.bin',
    note: 'nuke.php __lib=forum_favor2 __act=forum_favor action=get __output=8，登录态',
  },
  /** forum_favor2 action=get —— 一个都没收藏时 `data` 是空对象，连 `"0"` 键都没有。 */
  forumFavorEmpty: {
    contentType: 'text/javascript; charset=GBK',
    file: 'forum-favor-empty.gbk.bin',
    note: 'nuke.php __lib=forum_favor2 __act=forum_favor action=get __output=8，登录态、收藏为空',
  },
  /** forum_favor2 action=add|del 成功 —— `data["0"]` 是文本「操作成功」；del 未收藏的版块也回这个。 */
  forumFavorWriteOk: {
    contentType: 'text/javascript; charset=GBK',
    file: 'forum-favor-write-ok.gbk.bin',
    note: 'nuke.php __lib=forum_favor2 __act=forum_favor action=del fid=-7 __output=8，登录态',
  },
  /** forum_favor2 action=add 重复收藏 —— error「 你已经收藏了这个版面」（带前导空格）。 */
  forumFavorAlready: {
    contentType: 'text/javascript; charset=GBK',
    file: 'forum-favor-already.gbk.bin',
    note: 'nuke.php __lib=forum_favor2 __act=forum_favor action=add fid=-7 __output=8，重复收藏',
  },
  /**
   * nuke.php __lib=noti __act=get_all —— 没有任何通知的账号（2026-08-08 抓取）。
   * 关键形状：`data["0"]` 不是对象而是**空串**，通知解析必须把它当空列表。
   * 响应里没有任何账号信息，无需脱敏；有通知的形状见 API 文档 §9.1
   * 与两份研报（测试账号抓不到带数据的样本，条目级用例用文档口径的向量）。
   */
  notiGetAllEmpty: {
    contentType: 'text/javascript; charset=GBK',
    file: 'noti-get-all-empty.gbk.bin',
    note: 'nuke.php __lib=noti __act=get_all __output=8，登录态，空账号',
  },
  /**
   * nuke.php ucp get uid=41417929 —— 一个普通用户的资料：有头像、有 BBCode 签名、
   * 有 `ipLoc`，`rvrc`/`fame` 是 15（显示成 1.5），没有 `email`/`adminForums`。
   */
  ucpUser: {
    contentType: 'text/javascript; charset=GBK',
    file: 'ucp-get-user.gbk.bin',
    note: 'nuke.php __lib=ucp __act=get uid=41417929 __output=8，登录态',
  },
  /**
   * 同一接口、uid=2 —— 带 `adminForums`（管理权限卡的样本，key 是 fid 且为负数）、
   * **负的 `rvrc`**（-11109 → -1110.9）与逗号串形态的 `medal`。
   */
  ucpAdmin: {
    contentType: 'text/javascript; charset=GBK',
    file: 'ucp-get-admin.gbk.bin',
    note: 'nuke.php __lib=ucp __act=get uid=2 __output=8，登录态',
  },
  /** 同一接口、不存在的 uid —— `{"error":{"0":"找不到用户"}}`，命中假错误白名单。 */
  ucpMissing: {
    contentType: 'text/javascript; charset=GBK',
    file: 'ucp-get-missing.gbk.bin',
    note: 'nuke.php __lib=ucp __act=get uid=999999999 __output=8，登录态',
  },
  /** 头像补充查询：URL 直接躺在 `data["0"]` 上（是字符串不是对象）。 */
  ucpAvatar: {
    contentType: 'text/javascript; charset=GBK',
    file: 'ucp-get-avatar.gbk.bin',
    note: 'nuke.php __lib=ucp __act=get_avatar uid=41417929 __output=8，登录态',
  },
  /** thread.php authorid=41417929 —— 某人的主题（我的主题）。`__ROWS` 是正常数字。 */
  threadUserTopics: {
    contentType: 'text/javascript; charset=GBK',
    file: 'thread-user-topics.gbk.bin',
    note: 'thread.php authorid=41417929 page=1 __output=8，登录态',
  },
  /**
   * thread.php authorid=41417929&searchpost=1 —— 某人的回复（我的回复）。三处坑都在里面：
   * 每条多一个 `__P` 子对象（回复本身）、**同一个 tid 会重复出现多条**（不能按 tid 去重）、
   * 末尾 8 条是 `denied:"1"` 的过期占位（`error` 写着「帖子发布或回复时间超过限制」）。
   * 另外 `__ROWS` 是**空串**，总数只能退回 `__T__ROWS`。
   */
  threadUserReplies: {
    contentType: 'text/javascript; charset=GBK',
    file: 'thread-user-replies.gbk.bin',
    note: 'thread.php authorid=41417929 searchpost=1 page=1 __output=8，登录态',
  },
  /**
   * 同一请求翻过头（page=500）—— 到底的信号是 error「2048:没有符合条件的结果」，
   * 而它在假错误白名单里；同一份响应**同时**带着 `data.__MESSAGE`，
   * 所以解出来是一页 0 条主题，而不是一个错误。
   */
  threadUserRepliesEnd: {
    contentType: 'text/javascript; charset=GBK',
    file: 'thread-user-replies-end.gbk.bin',
    note: 'thread.php authorid=41417929 searchpost=1 page=500 __output=8，登录态',
  },
  /**
   * thread.php key=炉石（UTF-8）—— 全站主题搜索第一页。形状与版块列表完全一致
   * （`__F` 是空对象、`__ROWS` 是有效总数 46020）；`__CU.uid` 已脱敏成 10000001。
   */
  threadSearchKey: {
    contentType: 'text/html',
    file: 'thread-search-key.gbk.bin',
    note: 'thread.php key=%E7%82%89%E7%9F%B3(炉石 UTF-8) page=1 __output=8 __inchst=UTF8，登录态',
  },
  /**
   * forum.php key=炉石（GBK %C2%AF%CA%AF）—— 版块搜索。条目直接以数字键挂在 data 上，
   * 每条 `{fid, stid, name, descrip, relevance, url, parent:{fid,name}}`；
   * 既有普通版块（stid=0）也有合集（stid 非 0、fid 是宿主版块），
   * `topic_misc_var` 有时是空串有时是对象。响应里没有任何账号信息。
   */
  forumSearchKey: {
    contentType: 'text/html',
    file: 'forum-search-key.gbk.bin',
    note: 'forum.php key=%C2%AF%CA%AF(炉石 GBK) __output=8，登录态',
  },
  /**
   * forum.php 没有结果 —— error「2048:没找到符合条件的版面」（`没找到` 在假错误
   * 白名单里），data 只剩 `__MESSAGE`。UTF-8 编码的中文 key 也会落到这条：
   * 服务端按 GBK 解 percent 字节，解出来是乱码自然搜不到——GBK 编码就是这么验出来的。
   */
  forumSearchNone: {
    contentType: 'text/javascript; charset=GBK',
    file: 'forum-search-none.gbk.bin',
    note: 'forum.php key=<不存在的关键词 GBK> __output=8，登录态',
  },
  /**
   * topic_favor_v2 list_folder —— 两个收藏夹：`默认` 键 + `type: 2` 标出默认夹，
   * `length` 是夹内主题数。夹名是抓包时没带 `__inchst=UTF8` 产生的 mojibake
   * （UTF-8 字节被服务端按 GBK 落库），恰好留作「名字原样透传」的样本。
   */
  favorFolders: {
    contentType: 'text/javascript; charset=GBK',
    file: 'favor-folders.gbk.bin',
    note: 'nuke.php __lib=topic_favor_v2 __act=list_folder page=1 __output=8，登录态',
  },
  /** topic_favor_v2 list_folder —— 一个夹都没有：`data["0"]` 是空对象。 */
  favorFoldersEmpty: {
    contentType: 'text/javascript; charset=GBK',
    file: 'favor-folders-empty.gbk.bin',
    note: 'nuke.php __lib=topic_favor_v2 __act=list_folder page=1 __output=8，登录态，无收藏夹',
  },
  /** topic_favor_v2 new_folder —— 新夹 id 在 `data["1"]`，`data["0"]` 是「操作成功」。 */
  favorNewFolder: {
    contentType: 'text/javascript; charset=GBK',
    file: 'favor-new-folder.gbk.bin',
    note: 'nuke.php __lib=topic_favor_v2 __act=new_folder raw=3 __output=8，登录态',
  },
  /**
   * thread.php?favor=<夹id> —— 收藏夹的主题列表，形状与版块主题列表一致
   * （`__F` 是空对象；`tpcurl` 里带 `fav=:F…` 形式的访问码）。
   */
  favorTopics: {
    contentType: 'text/html',
    file: 'favor-topics.gbk.bin',
    note: 'thread.php favor=<夹id> page=1 __output=8，登录态',
  },
  /**
   * read.php tid=3593852 —— 网事杂谈（fid=-7）的版头帖，2010 年发的老帖。
   * 正文顶部那张图写的是**绝对地址** `https://img.nga.178.com/attachments/mon_202006/03/…png`，
   * 而 178 那个域名已经停了（TLS 握手失败），同一条路径挂在当前
   * `_ATTACH_BASE_VIEW`（`img.nga.cn/attachments`）下仍然是 200——
   * 「老域名重挂到响应给的基址」那条规则的样本（M2 遗留缺陷 2）。
   */
  readBoardHead: {
    contentType: 'text/javascript; charset=GBK',
    file: 'read-board-head.gbk.bin',
    note: 'read.php tid=3593852 page=1 __output=8 v2=1，登录态',
  },
  /**
   * thread.php key=第六感 —— 主题搜索。两个 M2 遗留缺陷的同一份样本：
   * 首条标题是 `&lt;第六感&gt;那个小孩…`（`subject` 也被 HTML 转义了），
   * 34 条里有 10 条是 `denied:"1"`、`author` 空串、`authorid` 0 的服务端提示行
   * （`error` 写着「帖子发布或回复时间超过限制」）。`__CU.uid` 已脱敏成 10000001。
   */
  threadSearchSixthSense: {
    contentType: 'text/html',
    file: 'thread-search-sixth-sense.gbk.bin',
    note: 'thread.php key=%E7%AC%AC%E5%85%AD%E6%84%9F(第六感 UTF-8) page=1 __output=8 __inchst=UTF8，登录态',
  },
  /**
   * thread.php?fid=414 `__output=8` —— **故意留着的坏样本**（2026-08-14 真机验收）。
   *
   * 游戏综合讨论第一页。声明 GBK，但某条主题的 `jdata.skw` 里塞的是 UTF-8 字节
   * （原文「独立游戏」），另有 GBK / GB18030 都解不出的字节（0xac @21539、0x80 @27131）。
   * 解码器只能吐 U+FFFD，替换字符又落在 `\"` 转义上，`JSON.parse` 必挂——
   * 这就是「414 在 app 里永远打不开」的根因，别把它当损坏文件删掉。
   *
   * `lite=js` 档拿到的是**同一份字节**（只多包一层 `window.script_muti_get_var_store=`），
   * 所以那一档救不了；能救的是下面的 `__output=11`。
   */
  threadListBusyBroken: {
    contentType: 'text/html',
    file: 'thread-list-fid-414-output8-broken.gbk.bin',
    note: 'thread.php fid=414 page=1 __output=8，游客身份；服务端返回的字节本身就坏',
  },
  /**
   * thread.php?fid=414 `__output=11` —— 上面那份的可用替身，同一页同一时刻抓的。
   *
   * 另一个序列化器（它转义 `/`），100 条主题解得干干净净；`__T` 记录的字段除了
   * 少一个全仓库没人读的 `__TJ` 之外与 `__output=8` 完全同构，所以 core/api
   * 一行都不用改。`DEFAULT_ROTATION_FORMATS` 把 `jsonVerbose` 放回轮换的依据就是这份。
   */
  threadListBusyVerbose: {
    contentType: 'text/html',
    file: 'thread-list-fid-414-output11.gbk.bin',
    note: 'thread.php fid=414 page=1 __output=11，游客身份',
  },
} as const satisfies Record<string, ApiFixture>

export type ApiFixtureName = keyof typeof API_FIXTURES

export function readFixtureBytes(name: ApiFixtureName): Uint8Array {
  return new Uint8Array(readFileSync(join(fixturesDir, API_FIXTURES[name].file)))
}

export function fixtureContentType(name: ApiFixtureName): string {
  return API_FIXTURES[name].contentType
}
