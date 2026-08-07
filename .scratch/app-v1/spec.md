# app-v1 · NGA 阅读器完整方案(grilling 共识记录)

Status: confirmed 2026-08-07(三轮 grilling 全部落定)
术语见根目录 `CONTEXT.md`;两条关键决策见 `docs/adr/0001`、`0002`。

## 1. 范围

**做**:功能文档 §2 的全部功能,除下列排除项。UI 以 `design/project/NGA客户端.dc.html` 的 31 屏为准,1:1 还原(token、字号、间距、动效)。

**排除**(入口保留,点击 toast「本版本未开放」):
- 回帖/发新帖:编辑器两页 + post.php 发帖/回帖/引用/编辑提交链路
- 短消息三页:列表、会话详情、新建短信
- 发贴条(已有贴条**展示**保留)、举报
- 投票**操作**(投票内容只读渲染;点击投票按钮 toast)

**保留的写操作**:点赞/点踩、收藏(主题多收藏夹 + 版块收藏)、签到、子版块订阅/屏蔽、修改签名、官方屏蔽词增删(set_block_word)。

**设计稿缺失页面**(按现有设计语言自行延伸,验收时过):搜索结果三种列表、签到入口(抽屉「登录账号」下加一行)、域名切换/图片策略等设置子对话框。

## 2. 平台与发布

- 仅 Android,竖屏手机(390 逻辑宽设计),平板不适配
- 仅个人使用;EAS 云构建(本地有 eas-cli),development profile 出 apk,真机 USB 验收
- 深链:intent-filter 接管 4 个官方域名的 thread.php/read.php + 自定义 scheme
- 命名:App 显示名「NGA 阅读器」,Expo slug `ng2`,owner `lemoncola`,包名 `com.chasel.ng2`,scheme `ng2`
- 参考同账号项目 yamibo-m 的 EAS 配置(dev/preview=apk internal,production=aab)

## 3. 技术栈(Expo SDK 57 基线,2026-08 查证)

- Expo SDK 57(RN 0.86 / React 19.2,New Arch 唯一),TypeScript strict,expo-router(版本随 SDK,57.x)
- TanStack Query(服务端数据)、Zustand(客户端状态)
- react-native-mmkv v4(设置持久化;**必须同装 react-native-nitro-modules**)
- expo-sqlite(帖子缓存/浏览历史/通知已读/本地屏蔽规则)
- expo-secure-store(账号凭证)
- expo-image(图片+磁盘缓存)、FlashList(长列表)
- 样式:StyleSheet + `tokens.ts`(从设计稿 Design Token 表生成,浅/深两套);不用 NativeWind
- pnpm;core 层单测 Vitest(真实抓包样本做 fixture)
- **纪律**:fetcher 禁止 clone response(expo/expo#47762 Android 乱序 bug)

## 4. 架构

```
src/core/    纯 TS,零 RN 依赖,可单测
  ├─ net/      fetcher 策略链(ADR-0002)、UA/域名/格式参数、GBK 编解码、响应清洗(API文档 §0.6)
  ├─ api/      thread/read/forum/nuke/app_api 各服务,手工字段遍历 + 类型容错
  ├─ bbcode/   BBCode → AST 解析器(标签清单=功能文档 §2.9 并集)、两轮实体解码
  └─ local/    匿名还原、骰子、彩色标题解码、热帖聚合、通知已读模型
src/app/     expo-router 路由(31 屏)
src/ui/      AST→组件渲染器、楼层卡片、token 主题
src/store/   Zustand + 持久化
```

- 表情:构建脚本从 CDN(`{IMGPATH}/post/smile/…`)批量下载 ~240 张打包进 assets;映射表从 NGA 官方 `js_bbscode_core.js` 提取(**不可**从 GPL-2.0 的 Justwen 仓库复制);远程 URL 作兜底;「熊猫」套需从线上 JS 现抓核实是否存在
- 通知:前台 60s 轮询,已读纯本地(`时间戳-类型-tid-pid` 稳定 ID),无系统通知/后台任务
- 阅读进度:记「上次读到楼层」,进入时提示条跳转;历史 200 条
- 缓存:浏览自动写 SQLite(LRU 上限)+ 手动缓存本页/整帖;不做 zip 导入导出
- 默认:域名 bbs.nga.cn、排序按最后回复、热帖仅 24h 档

## 5. 里程碑(拆 issue 时细化)

- **M1 能读**:core 层全量(含单测)+ 首页→主题列表→帖子详情主链路 + 浅深色 token;fetcher 策略链框架先行(策略后填)
- **M2 有身份**:WebView 登录、多账号、收藏(帖+版+多夹)、点赞、通知+被喷页、搜索(含延伸的结果页)、历史/进度
- **M3 打不死**:反封锁链填满(格式交替→换号→Web反解→缓存→网页兜底页)、我的缓存页、屏蔽规则(本地+官方云同步)、设置三屏、签到、深链
- **M4 1:1 打磨**:逐屏对照设计稿、大图查看器手势、回复链视图、动效、字号调节实时预览

## 6. 联调资源

- 测试 cookie 在 `.env.local`(gitignored;NGA_UID/NGA_CID),已验证有效(noti 接口返回合法 data)
- 对拍样例:tid 45150945、fid 650、uid 41417929(来自 MNGA 集成测试)
- 接口解析出问题先查 Justwen fork 最新提交(仅参考协议,不抄代码)

## 7. 法务/许可边界

- Justwen/NGA-CLIENT-VER-OPEN-SOURCE 为 **GPL-2.0**:只读协议行为,不复制代码与数据表
- MNGA 无 license:只参考思路
- 表情图等 NGA 素材:仅个人使用,不分发
