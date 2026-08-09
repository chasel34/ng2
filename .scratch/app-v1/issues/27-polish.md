# 27 — 全局 1:1 验收打磨

**What to build:** 对照设计稿逐屏走查修偏差(字号/间距/圆角/颜色/图标粒度);统一动效(上滑入场/淡入/弹出,时长与缓动照原型);左手模式真实生效(FAB 与菜单镜像);关于页;替换占位 app 图标与启动屏为正式设计;全局空态/加载态统一;真机全功能回归一遍。

**Blocked by:** 01–26 全部

**Status:** implemented(代码侧全部完成;真机验收待跑,清单见 `m4-acceptance.md`)

- [x] 31 屏逐屏对照清单全部勾销 —— 静态对照见 `m4-screen-audit.md`(8 屏一致 / 14 屏共 41 处偏差已修 / 5 屏是 spec §1 排除项);**截图存档留给真机验收**
- [x] 三种动效曲线与原型一致 —— 收进 `src/ui/motion.ts`,全局套用
- [x] 图标/启动屏替换完成 —— `scripts/make-app-icons.mjs` 生成;**冷启动无白屏闪烁需真机验(m4-acceptance A4/A5)**

## Comments

### 1. 逐屏对照(第 1 项)

四个只读子代理分片把设计稿 31 屏的内联 style 逐元素抽成规格,与 `src/` 的 StyleSheet
逐条比对,主控统一改。结论与逐屏明细在 `.scratch/app-v1/m4-screen-audit.md`。

要点:

- **`tokens.ts` 新增 7 档**(字号 5 + 圆角 2),消灭 5 处散写的魔法字号。
  `radius.sm` 原本兼职「分类标签 / 页码格」两个值——Design Token 表那一档写的就是
  「radius/sm 8–9」的区间,设计稿两个端点都在用,拆成 `xs: 8` / `sm: 9`。
- **顶栏按钮从一刀切 46 拆成两档**:返回/菜单 46、右侧动作钮 44(设计稿逐屏都是这么标的;
  唯一例外是大图查看器的保存/分享,那两枚是 46)。21 个调用点跟着改,这是本票影响面最大的一处。
- **`describeFetchFailure` 的返回值改了形状**:`{ headline, hint }` → `{ headline, code?, hint }`。
  设计稿只把状态码那一截加粗换等宽,前半句是普通正文;原本整句(含中文前缀)都被
  渲染成等宽粗体。拆字段比让页面去猜从哪儿断开靠谱。单测同步改。
- **未修的两处 ≤2px 细偏差**(isProfile 的卡片内距与说明行下距)要引入「第几张卡」的
  位置变体才能表达,记为已知差异。

### 2. 动效(第 2 项)

新增 `src/ui/motion.ts`。设计稿第 38–40 行只声明了三条 keyframe,整份原型的入场动画
都是它们的组合;每处用法的时长写在 markup 里,缓动一律是 CSS 的 `ease`。

关键发现:**CSS `ease` 是 `cubic-bezier(.25,.1,.25,1)`,不是 RN 的 `Easing.out(quad)`**。
两条曲线尾段差得挺明显(quad 收得更急),而全项目原本只有详情页「上次读到」提示条
用对了,其余七八处一律 `Easing.out(quad)`。现在统一从 `easeStandard` 取。

收敛的常量:`duration` 五档(menu 160 / quick 180 / base 200 / panel 220 / notice 280)、
`RISE_OFFSET = 14`、`POP_SCALE = 0.94`、横滑翻页专用的 `easeDecelerate`、
以及 expo-router 的 `screenTransition`。

套用面:弹出菜单、五个对话框、抽屉、Snackbar、设置开关、FAB 展开与图标旋转、
横滑回弹、屏幕转场。

顺带修好三处「设计稿有、实现没有」的动效:

- **对话框遮罩不淡入** —— 五个对话框的 scrim 都是根 View 的静态底色,开框瞬间满黑,
  只有面板在弹。抽了 `src/ui/overlay.tsx` 的 `useOverlayAnimation` + `OverlayScrim`,
  遮罩 180ms / 面板 200ms 并行。
- **FAB 展开的动作列没有动画** —— 直接出现。补上 omup .18s。
- **FAB 图标是换字形不是旋转** —— 设计稿是同一枚 `add` 转 45° 变成 `×`,`transition .2s`。
  改成 `Animated` 旋转。

另外四个对话框的起始缩放写的是 `.92`(设计稿是 `.94`),Snackbar 的位移写的是 16
(设计稿 keyframe 是 14)——都归位了。

### 3. 左手模式(第 3 项)

22 票只存了值,这次接线。新增 `useLeftHanded()`(`ui/appearance.ts`,与其它设置消费口
放一起),消费方三处:

- `OverflowMenu` 自己读设置,所有调用点(7 处)一次性镜像;`transformOrigin` 跟着换边,
  免得缩放动画从一个够不着的角上长出来
- 帖子详情的 FAB 与它展开的动作列(动作列还要换 `alignItems` 方向)
- 主题列表页与屏蔽规则页的 FAB

**只镜像浮在内容上的控件**。顶栏按钮、列表行、对话框按钮不动——它们在页面流里,
镜像了就是换一套排版而不是换一只手。

### 4. 关于页(第 4 项)

设计稿**有**这一屏(`isAbout`,774–800 行),之前抽屉那条是 toast「本版本未开放」。
新建 `src/app/settings/about.tsx`,顶部标识块 / 五行内容 / 底部免责声明的度量照抄。

设计稿那五行是给公开发行的客户端画的(检查更新 / 开源地址 / 反馈问题 / 开源许可 /
免责声明)。这个客户端只给自己用:没有更新服务器、没有仓库地址、也没有 issue 收件人,
所以前三行换成本机说得出口的三件事——数据来源、系统设置、诊断日志——行的形状与顺序不动。

其中「系统设置」这一行是**M3 缺陷 1 的落点**:Android 12+ 要用户自己去系统设置开
「打开支持的链接」,NGA 域名不归我们控制、assetlinks.json 的自动验证走不通,
`Linking.openSettings()` 至少把用户送到该去的地方。

版本号走 `expo-constants` 读 `app.json`,不写死——「用户报的版本」与「装的那一版」
对不上是最难查的一类问题。

顺带:关于屏的五个图标(update / code / bug_report / description / gavel)之前不在
生成的码点表里,因为 `scripts/fetch-icon-font.mjs` 的扫描正则只认 `icon:'…'` 那种写法,
认不出 `T.aboutRows` 那种「图标名打头的三元组数组」。补了一条正则后重跑,
表从 77 涨到 84 个图标。

### 5. 图标与启动屏(第 5 项)

`assets/` 之前整个是 Expo 模板的默认件(icon.png 是 Expo 那个 1024 的图,
splash-icon.png 就是 expo-logo.png)。新增 `scripts/make-app-icons.mjs`:
SVG 源写在脚本里,`rsvg-convert` 光栅化,一次出 7 张。

造型直接取设计稿:关于屏的应用标识就是墨绿底 + 白「NG」;底纹取版块图标与资料页
banner 那套 135° 斜纹。颜色全是 `tokens.ts` 的值。

- 自适应图标分三层:背景(墨绿 + 斜纹)、前景(白字标,缩到中间 66% 安全区内,
  各家 ROM 的圆/方/水滴裁切都不会切到字)、单色层(Android 13+ 主题图标)
- **启动屏背景改成页面背景而不是顶栏墨绿**:浅色 `#FCF4E1` / 深色 `#1C1C1B`,
  字标相应换成墨绿。启动屏一收就是页面本身,底色一致才看不出那一下切换。
  `expo.backgroundColor` 与 `android.backgroundColor` 也铺了同一档
- 顺手删了 Expo 模板残留的 8 张示意图与 `tabIcons/` `expo.icon/`(代码里零引用)

`rsvg-convert` 是机器本地依赖(`brew install librsvg`),脚本里检查并给出提示。
这一步一年跑不了几次,不值得为它进一个构建期依赖——与 `fetch-icon-font.mjs`
依赖网络是同一个取舍。

### 6. 空态 / 加载态(第 6 项)

原本 15 个屏各写各的 `styles.empty` / `styles.center`,图标 36–44、间距 8–16 都有;
更要紧的是失败态普遍把 `error.message` 直接摆到屏幕上——那正是 M3 缺陷 3 的那一类
(反封锁链末端抛的是 `fetch failed: java.io.IOException: …`)。

新增 `src/ui/state-view.tsx`,三个组件定死口径:

- `LoadingState` —— 正在拉
- `EmptyState` —— 拉成功但没内容(40 号 meta 图标 + notice 文案 + 可选出路)
- `LoadingFooter` —— 列表底部「正在载入第 N 页…」

失败态一律交给已有的 `LoadFailedNotice`(走 `describeFetchFailure` 的友好文案)。
与 `error-screen.tsx` 的分工:那边是「拉失败了」,这边是「拉成功但没内容」和「正在拉」。

14 个屏改完,顺手消掉 8 处「把底层异常摊给用户」的路径。搜索页原本有个同名的本地
`EmptyState`,改名 `SearchOutcome` 并改成分派到共用组件。

### 7. 工程

- `pnpm exec tsc --noEmit` 通过
- `pnpm test` 76 passed / 4 skipped,1049 tests passed
- 未装任何新依赖。(中途 `pnpm lint` 自动装了 eslint + eslint-config-expo 并生成了
  `eslint.config.js`——已全部回滚,`package.json` / `pnpm-lock.yaml` 干净)
- 样式全部走 tokens/theme,新增的档位都在 `tokens.test.ts` 里登记了出处

### 8. 真机验收(留给下一轮)

本环境跑不了真机,整理成 `.scratch/app-v1/m4-acceptance.md`,10 组共 60 余项,
覆盖 25 / 26 / 27 + M3 缺陷 2/3/4 复验。

**前置:必须重出 dev build**——25 的原生模块、27 的图标/启动屏/背景色全是构建期资源,
热更 JS 验不到。

清单里最要紧的几项:A4/A5 冷启动无白屏(本票唯一无法静态验证的硬指标)、
B6 FAB 的两段新动效、C 组左手模式六项、E9 大图查看器 16 内距**要现场定夺**
(照设计稿留白 vs 全屏看图哪个更重要)、F4 断网下九个屏都不漏底层报错。
