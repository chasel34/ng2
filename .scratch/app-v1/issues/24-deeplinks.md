# 24 — 深链 + URL 读取

**What to build:** 在微信/浏览器等处点 NGA 四个官方域名的主题/版块链接,系统弹出用本 app 打开,直达对应详情页(含 tid/page/pid 参数)或列表页(fid/stid);自定义 scheme 同样可跳;抽屉「由 URL 读取」对话框粘贴链接解析跳转,不合法链接有提示。

**Blocked by:** 05, 07

**Status:** implemented

- [x] read.php(tid/page/pid/fav)与 thread.php(fid/stid)参数解析单测
- [ ] 真机验证外部点击链接进入正确页面
- [x] 对话框与设计稿 1:1

## Comments

- 解析全在 `src/core/local/deep-link.ts`(纯 TS,26 条单测)。认三种写法:官方域名的 http/https 链接、自定义 scheme `ng2://read.php?tid=`、以及手粘时省掉 scheme 的 `bbs.nga.cn/read.php?tid=` / `/thread.php?fid=`。read.php 取 tid(必需)+ page/pid/fav;query 没写 pid 时按网页锚点 `#pid<pid>Anchor` 取;thread.php 取 stid/fid,stid 优先(CONTEXT.md)。失败收成 `{ ok: false, reason }` 五种原因,各自有中文文案。
- 落地路径由 `ngaLinkPath()` 一处拼(`/topic/<tid>?page&pid&fav`、`/board/<id>?kind=`),`+native-intent` 与「由 URL 读取」共用,免得两处参数映射走偏。
- `src/app/+native-intent.ts` 的 `redirectSystemPath`:解不出来**原样放行**——dev client 自己的启动 URL 和没带深链的冷启动(`ng2:///`)也从这个口子过,一律改写会让开发包打不开。
- `_layout.tsx` 加了 `unstable_settings = { anchor: 'index' }`:深链冷启动时栈里只有落地页,各页顶栏的 `router.back()` 会点不动,垫一层 index 才有得退。
- 对话框文案照设计稿(标题「由 URL 读取」/ hint「支持 read.php / thread.php 链接」/ 按钮「打开」),复用 05 的 `InputDialog`,只给它加了个 `error` prop:链接解不开时**不关框、不跳转**,就地把 hint 那一行换成红字(设计稿没画这个态,位置与字号不动,只换颜色)。
- **intent-filter 变更要重新出包才生效**(managed workflow,`android/` 由 EAS prebuild 生成),现有真机上的旧包点链接不会有反应。app.json 里改成了 Android 的 `<data>` 合并写法(同一 intent-filter 内 scheme × host × pathPrefix 交叉匹配,Android 官方文档明示的等价形式),顺带补上 `http` 与第五个官方域名 `nga.donews.com`,与 `core/net/constants.ts` 的 `NGA_HOSTS` 对齐。
- 留待真机:① 微信/浏览器里点 NGA 主题与版块链接,确认系统弹「用 NGA 阅读器打开」且落在正确页面(带 page/pid 的链接要看是否开在对应页/对应楼);② `adb shell am start -a android.intent.action.VIEW -d "ng2://read.php?tid=45150945&page=2"` 验自定义 scheme;③ 深链落地后点顶栏返回是否退到首页。
