# 14 — 用户资料页 + 我的主题/回复

**What to build:** 从楼层头像/用户名进入资料页:banner 头部(头像/用户名/UID)、基础信息卡(邮箱/用户组/发帖数/金钱铜银金换算/状态/注册日期/属地)、签名卡、管理权限卡、声望条形图;威望 ÷10 显示;NUKED/禁言状态标注;顶栏私信图标 toast 占位。抽屉菜单「我的主题」「我的回复」两个列表页(回复条目跳对应楼层)。

**Blocked by:** 05, 09

**Status:** resolved

- [x] ucp 接口必带 Referer,找不到用户与空 user 区分处理(单测)
- [x] 金钱换算与威望显示规则单测
- [x] 资料页四张卡与设计稿 1:1;头像缺失时走补充查询再走首字占位

## Comments

**core 层**

- `src/core/local/money.ts` —— 两条纯显示换算 + 单测。`splitMoney(铜币总数)` 按 10000/100 两级进位拆金银铜(负余额取绝对值拆再单独标 `negative`,直接对负数取模会拆出 `-1.-23.-45` 这种读不出来的东西);`formatMoney` 给设计稿的 `金.银.铜` 文案。`toReputation(rvrc)` = ÷10,`formatReputation` 收的是**已经除过 10** 的显示值——领域模型里存的就是它,UI 不该再碰服务端那个原始整数。`topic-detail.ts` 原本自带的 `REPUTATION_SCALE` 常量与 `floor-card.tsx` 里那个私有 `formatReputation` 都收敛到这里。

- `src/core/api/user-profile.ts` —— `nuke.php?__lib=ucp` 的解析与请求。两处坑各有单测钉着:

  1. **Referer 必带**。新加了 `NgaRequest.refererPath`(只写路径,由策略补当前 host),而不是写死一个完整 URL——反封锁链会换域名,Referer 得跟着走。单测同时锁了默认域名与 `host: 'https://ngabbs.com'` 两种情况。
  2. **「找不到用户」在假错误白名单里**,`parseNgaJson` 会把它当成功返回、`data` 是 undefined。所以 `fetchUserProfile` 自己分三种情况:没有 data → 报服务端原话「找不到用户」;有 data 但 `data["0"]` 空 → 报「资料响应里没有用户」;正常 → 解析。两句话不同是有意的,排障时区分得开「查无此人」和「响应是空的」。

  字段大半是可选的:实测只有查自己时服务端才给 `email`/`phone`,`adminForums` 只有真担任职务的号才有,`ipLoc` 没记录时是占位文案「尚无记录」(不当地名显示)。状态判定 `verified/yz === -1` → NUKED,`muteTime` 还没到期 → 禁言,**NUKED 压过禁言**。`reputation`(各版声望)抓包里一次都没见过,解析器两种可能形状都收(`{fid: 数}` 与 `{fid: {name,value}}`),解不出名字退回 `版面 <fid>`。

- `src/core/api/user-topics.ts` —— 「我的主题/我的回复」。仍是 `thread.php`,**响应形状和版块列表一模一样**,所以直接复用 `parseTopicList`;`Topic` 只加了两个可加性字段:`reply?`(`__P` 里那条回复:pid/正文/时间)与 `denied`(服务端拒给内容)。三处只在这条路径上才踩得到的坑:

  1. **不能按 tid 去重**(`mergeTopicPages` 就是那么干的)——在一个帖子里回了 10 层就是 10 条,`mergeUserPostPages` 按 `reply.pid` 去重,没有 reply 才退回 tid。
  2. **翻页不能看 `totalPages` 也不能看「这一页装满了没有」**:回复列表的 `__ROWS` 是**空串**(`int('')` 会读成 0,顺手修成退回 `__T__ROWS`),而且实测每页只回 18~19 条却还有后续页。判据只能是「这一页一条都没有」。
  3. **翻过头的信号是 error「2048:没有符合条件的结果」**,也在假错误白名单里,而且同一份响应还带着 `data.__MESSAGE`——归一成空页而不是抛错(fixture `thread-user-replies-end.gbk.bin` 锁住)。

**fixture**(真实抓包,已脱敏:抓包账号 uid → 10000001,cookie 不在响应体里)

`ucp-get-user.gbk.bin`(对拍样例 uid 41417929:有头像/BBCode 签名/ipLoc,rvrc 15 → 1.5)、`ucp-get-admin.gbk.bin`(uid 2:`adminForums` 且 fid 为负、rvrc -11109 → -1110.9)、`ucp-get-missing.gbk.bin`(找不到用户)、`ucp-get-avatar.gbk.bin`(URL 直接躺在 `data["0"]` 上,是字符串不是对象)、`thread-user-topics.gbk.bin`、`thread-user-replies.gbk.bin`(带 8 条 denied 占位)、`thread-user-replies-end.gbk.bin`。另有 `user-profile.smoke.test.ts` 联网冒烟(`NGA_INTEGRATION=1` 才跑),已实跑通过。

**页面**

- `src/app/user/[uid].tsx` 照设计稿 `isProfile` 屏:斜纹 banner(RN 没有 `repeating-linear-gradient`,拿等距旋转窄条铺)+ 62 头像 + 用户名 + UID;四张卡——基础信息(两列网格)、签名(BBCode 走楼层同一个渲染器)、管理权限(chips)、声望(条形图,按本人各版最大绝对值归一化,负值走 danger 色)。**签名/管理权限/声望三张卡没数据就整卡不画**,不拿空壳占位。顶栏私信图标 toast「本版本未开放」。设计稿那八格里没有威望,补了一格在金钱后面(功能文档明确资料页要有声望/威望)。
- `src/store/user-profile.ts`:**头像缺失时先补一次 `ucp get_avatar`**(API 文档 §11.2),补查失败就吞掉让 UI 走首字占位——不该为一张头像把整页变成错误页。`staleTime` 5 分钟,从楼层反复点同一个人不会反复打 ucp。
- `src/app/user/posts.tsx`:我的主题/我的回复同一个屏,只差一个 `kind` 路由参数。主题行复用 `TopicRow` 的 simple 档,回复行是新的 `src/ui/reply-row.tsx`(主角是回复摘要、主题标题降到信息行)。denied 条目点了给 toast 说明理由,不往空帖子里跳。

**「回复条目跳对应楼层所在页」的落地方式(与票面措辞有出入,先说明)**

**NGA 不提供 pid → 页码的换算**,我逐个试过:`read.php?pid=`、`&searchpost=1&pid=`、`&tid=&pid=`、`&opt=128`、`&topage=`、`&page=0` —— 全部只把那一楼单独捞出来,`__PAGE` 恒为 1、`__ROWS` 恒为 1,而且 `lou` 被重编成 0。「只看某人」模式(`&authorid=`)的 `lou` 同样是重编过的 1..N,不是真实楼层号,所以也换算不出来。13 票的通知能落到页是因为 **noti 接口自己就下发页码**(字段 `"10"`),thread.php 的 `__P` 里没有这个东西。

剩下的选择只有二分整帖页(热帖那种大帖要十来个 read.php,正撞 ADR-0002 的封号风险)或者用 NGA 自己那套「只看该楼」。取了后者——API 文档 §3 给 `pid` 的定语就是「只看某一楼(从通知跳转时用)」。详情页多认一个 `pid` 路由参数,顶上带一条「只看该楼 / 看全部」的提示条,点一下清掉回整帖。

**顺带改到的共享文件(都很小、可加性)**

1. `src/core/net/types.ts` + `strategies/direct.ts` —— 新增可选 `refererPath`,`Referer` 从 `${host}/${refererPath ?? ''}` 拼。原有行为(不传时是 `${host}/`)一字未变。
2. `src/core/api/types.ts` —— `Topic` 加 `reply?` / `denied`,新增 `UserProfile`/`AdminForum`/`ReputationEntry`/`UserStatus`/`TopicReply`。
3. `src/core/api/topic-list.ts` —— 解 `__P` 与 `denied`;`__ROWS` 为空串/0 时退到 `__T__ROWS`(原来会算成「共 0 条 / 共 1 页」)。
4. `src/core/api/topic-detail.ts` —— `fetchTopicDetail` 多认一个可选 `pid`;`REPUTATION_SCALE` 改从 core/local 取。
5. `src/ui/floor-card.tsx` —— 头像与用户名点进资料页,**匿名楼层不可进**(`disabled`,没有真身 uid);私有的 `formatReputation`/`plainTextOf` 换成共享实现。
6. `src/ui/app-drawer.tsx` —— 加「我的主题」「我的回复」两条,`DrawerEntry` 加可选 `mine` 字段(目标 uid 是当前账号,得登录后才知道);游客态走 `showLoginPrompt`。
7. `src/app/topic/[tid].tsx` —— 多认一个可选 `pid` 参数(只看该楼)+ 一条提示条。只看该楼时**不记阅读进度也不提示续读**:屏上只有一楼、`totalRows` 是 1,照记会把 16 票存的历史楼数覆盖成 0。
8. `src/ui/bbcode/plain-text.ts` —— 把 `floor-card` 里那个私有的 BBCode 压平函数提出来,贴条与回复摘要共用。

### 遗留问题

1. **真机没跑**,只有 core 单测(702 个全绿,含联网冒烟)+ typecheck。逐屏对照留给 27 票。要重点看:斜纹 banner 在真机上的观感、基础信息卡两列在长值(打码邮箱)下会不会顶行、只看该楼的提示条。
2. **声望卡没有真样本**。抓包试了 6 个账号都没有 `reputation` 键,解析器是按 API 文档口径写的两形状兼容,真机遇到有声望的账号后要复核一次字段形状与条形图观感。
3. **金钱对普通用户恒为 0**。实测查别人时服务端一律给 `money: 0`(查自己也是 0,因为测试号是新号),换算规则本身有单测,但真机上要拿一个有钱的号确认它确实会下发。
4. **「我的回复」列表后段大量是 denied 占位**(帖子过期就不给正文),实测第 10 页起整页都是。目前照实展示并在点击时说明理由;真机看下来如果太吵,可以考虑在设置里加一个「隐藏已过期的回复」。
5. **顶栏「更多」菜单**仍是 toast 占位(拉黑/看 TA 的主题等归 21 票与后续票)。
