# M4 模拟器验收轮(派发给 codex)

本轮换到 **Android 模拟器**验收(用户指定:先模拟器过一遍,通过后再上真机复验)。
执行者 codex,**只验收不改代码**;结论按下面「汇报格式」追加到本文件末尾。

前情:M4 真机验收 + 三轮修复见 `m4-acceptance-results.md`、`m4-fix-verification.md`,
清单全文 `m4-acceptance.md`。

## 环境(派发方已备好)

- 模拟器 serial **`emulator-5554`**(Pixel_8 AVD,Android 17 / API 37,1080×2400 左右),
  所有 adb 命令加 `-s emulator-5554`。
- `adb` / `emulator` 不在默认 PATH:先
  `export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"`。
- **dev client 是本机 gradle 打的 debug 包**(不再走 EAS 队列),包名 `com.chasel.ng2`。
  要重装:`android/app/build/outputs/apk/debug/app-debug.apk`。
- Metro 在 Mac 上跑 **8082**,已 `adb -s emulator-5554 reverse tcp:8082 tcp:8082`;
  断连后重做 reverse。app 停在启动页时用
  `adb -s emulator-5554 shell am start -a android.intent.action.VIEW -d "ng2://expo-development-client/?url=http%3A%2F%2Flocalhost%3A8082"`。

## 硬约束

- **严禁修改任何服务端/账号状态**:不改签名、不发帖/回帖、不点赞、不收藏/取消收藏、不改资料。
  需要样本就去论坛现有内容里找;找不到就标 ⚠️,不要造数据。
- **每验完一项立刻把该项结论写进本文件**(追加式)。你的会话此前多次死于 `Bad Request`,
  攒到最后一起写会丢结果。
- 拿不准观感的标 ⚠️ 留人定夺,不要自行放宽口径。
- 模拟器测不了的(真实高刷 120Hz、真机相机/相册体感、真实弱网)直接标
  「⏭️ 留真机轮」,不要硬凑。

## 控 token 打法(沿用)

- 先 `adb -s emulator-5554 shell uiautomator dump /sdcard/x.xml` + `grep` 判断布局/文案;
  截图只在必须看渲染观感时用(`exec-out screencap -p > /private/tmp/xxx.png`)。
- 动效用 `screenrecord`(≤30s)抽帧,别连拍整屏。
- 模拟器上系统设置可以随便改(深色模式、字号、网络),改完记得还原。

## 本轮范围

### A. A5 深色冷启动屏(真机轮欠的账,本地 debug 包能验)

修复内容:`src/app/_layout.tsx` 模块顶 `SplashScreen.preventAutoHideAsync()`,
图标字体就位后 `hideAsync()`;深色启动屏资源在 app.json(`#1C1C1B` + splash-icon-dark)。

- A5-1 系统切深色(`adb shell "cmd uimode night yes"`)→ `am force-stop` → 冷启动,
  录屏抽帧确认:启动屏底色是深色 `#1C1C1B`,**中间不闪浅色 `#FCF4E1`**,
  且启动屏不早收(收起时第一屏已经画好,没有白/浅底空窗)。
- A5-2 系统切浅色(`cmd uimode night no`)→ 同样冷启动,底色 `#FCF4E1`,图标为浅色版。
- 判定要点:抽帧看**首帧到首屏之间每一帧**的底色,只要出现一帧对不上就 ❌ 并给帧号。

### B. 修复项在新环境的回归复验

逐项复验,判定与真机轮一致才算过(证据路径写清):

- R-G1 图片查看器能正常渲染(不空白转圈),翻页计数 `n / N` 正常。
- R-H5 详情页 → 回复链 → 「在原帖中查看」:留在正确页 + 目标楼进视口(别被自动翻页抢走)。
- R-D4 关于页免责声明展开是长文,页脚仍是短版,两者不重复。
- R-E7 正文里的小图保持原尺寸行内显示,不被拉满卡宽。
- R-F4 断网时通知页文案是「连不上服务器 / 检查网络连接后重试」。
  **模拟器上断网比真机简单**:`adb -s emulator-5554 shell svc data disable` +
  `adb -s emulator-5554 shell svc wifi disable`(模拟器断 Wi-Fi 不会掉 adb,adb 走 tcp 转发),
  或 `adb emu gsm data off`。注意 Metro 也会断,所以**先把要测的页加载好再断网**,
  或断网前确认 bundle 已下载完(dev client 只在启动时拉 bundle)。测完 `svc wifi enable`。
- R-G8 同一张图重复保存去重:`Pictures/NGA` 文件数不增,toast 是「这张图已经在 相册/NGA 里了」/
  批量「这些图都已经在 相册/NGA 里了」。
- R-I5 抽屉头像点开进入**当前账号的资料页**(不是账号管理)。
- 滚动定性:主题列表甩滚有没有白块/闪烁(模拟器帧率不代表真机,**只判有无白块与内容错位**,
  卡顿快慢一律 ⏭️ 留真机轮)。

### C. 模拟器才好测的补测项(真机轮标了 ⚠️ 需人工的)

能测多少测多少,测不了写原因:

- A2 / A3 图标形状与主题图标:Pixel launcher 支持切图标形状(圆/方/squircle)与「主题图标」开关,
  在模拟器设置/壁纸样式里切,确认 adaptive icon 三层裁切正常、monochrome 图层随取色。
- G3 捏合缩放:模拟器可用 `adb shell input motionevent` 或 `sendevent` 多指注入;
  实在做不出双指手势就保持 ⚠️。
- F1 空态六屏:模拟器可以随便清数据(`pm clear com.chasel.ng2` 会清掉登录态,
  **清之前先跟派发方确认**,因为重新登录需要账号密码)。先测不需要清数据的空态。

## 登录态说明

**已由用户在模拟器上手动登录(账号 lemon43),登录态可用**,需要登录的项照常验。
但**不要退出登录、不要 `pm clear com.chasel.ng2`**——重新登录只能靠用户手动输密码。
F1 空态里要清数据才能造的那几屏,继续标 ⚠️。

## 汇报格式

追加 `## 模拟器轮结果(codex,日期)`,逐项 `✅ / ❌ / ⚠️ / ⏭️ / ⏸️` + 一行现象 + 证据路径。
❌ 多写复现路径。最后附环境还原确认(夜间模式、Wi-Fi/数据、图片策略、字号等改过的都还原)。

## 模拟器轮结果(codex,2026-08-12)

- ❌ **A5-1 深色冷启动屏** — 复现：`cmd uimode night yes` → `am force-stop com.chasel.ng2` → 以文档给定的 `ng2://expo-development-client/?url=http%3A%2F%2Flocalhost%3A8082` 冷启动（`am start -W` 确认 `LaunchState: COLD`）。逐帧可见深色 `NG` 启动屏后，第 94 帧（1.896s）首次出现奶油色 Launcher/开发客户端加载层，第 100 帧还显示 `Loading from 127.0.0.1:8082...`，第 109 帧才回到深色，第 120 帧首页已画好；首帧到首屏之间出现浅色帧，未达标。证据：`/private/tmp/m4-a5-1-dark-valid.mp4`、逐帧索引 `/private/tmp/m4-a5-1-dark-valid.csv`、关键帧 `/private/tmp/m4-a5-1-dark-valid-frames/f-0089.png`、`f-0094.png`、`f-0100.png`、`f-0109.png`、`f-0120.png`。
- ❌ **A5-2 浅色冷启动屏** — 复现：`cmd uimode night no` → `am force-stop com.chasel.ng2` → 同一 dev-client 深链冷启动（`LaunchState: COLD`）。浅色启动屏本身为奶油底 + 绿色 `NG`，但第 92 帧（1.694s）出现整屏黑帧，随后第 103–119 帧是仅有 dev-client `Tools` 悬浮钮的空奶油背景，第 120 帧首页才画好；既出现错色帧，启动屏也早于首屏收起，未达标。证据：`/private/tmp/m4-a5-2-light-valid.mp4`、逐帧索引 `/private/tmp/m4-a5-2-light-valid.csv`、关键帧 `/private/tmp/m4-a5-2-light-valid-frames/f-0071.png`、`f-0091.png`、`f-0092.png`、`f-0103.png`、`f-0120.png`。
- ✅ **R-G1 图片查看器渲染/计数** — 论坛既有 4 图主题 `tid=47337312` 中点开附件后查看器正常显示图片，无空白或持续转圈；左滑后计数从 `2 / 4` 正确变为 `3 / 4`，下一张仍完整渲染。全程未点保存、分享或任何互动按钮。证据：`/private/tmp/m4-rg1-viewer1.png`、`/private/tmp/m4-rg1-viewer2.png`，控件树 `/sdcard/m4-rg1-viewer1.xml`、`/sdcard/m4-rg1-viewer2.xml`。
- ✅ **R-H5 回复链“在原帖中查看”定位** — `tid=47347120` 第 2 楼进入“查看对话链(3 层)”，点击最下方第 19 楼的“在原帖中查看”；等待首滚和补偿校正后仍在第 1 页，视口稳定包含第 16–19 楼，第 19 楼头部、作者与正文均已进入视口，未被自动翻页抢到第 2 页。证据：`/private/tmp/m4-rh5-result.png`，控件树 `/sdcard/m4-rh5-chain.xml`、`/sdcard/m4-rh5-result.xml`。
- ✅ **R-D4 关于页免责声明** — 展开“免责声明”后显示以“本客户端是个人开发的第三方阅读工具”开头的完整长文（含无关联、版权归属、不缓存分发等说明）；常驻页脚仍是“本客户端与 NGA 官方无关…”短版，两段长度与措辞均不同，不再重复。证据：`/private/tmp/m4-rd4-about-expanded.png`，展开前后控件树 `/sdcard/m4-rd4-about-before.xml`、`/sdcard/m4-rd4-about-after.xml`。
- ✅ **R-E7 正文小图保持行内原尺寸** — 论坛既有内容 `tid=47347120` 第 16 楼正文末尾的小表情在 1080px 宽屏上仅约 `56×52px`，紧跟文字行内显示，没有铺满卡宽或撑高整卡；未修改签名、资料或论坛内容。证据：`/private/tmp/m4-rh5-result.png`（画面顶部第 16 楼），控件树 `/sdcard/m4-rh5-result.xml`。
- ✅ **R-F4 通知页断网文案** — 在线确认当前通知页为空后返回，临时执行 `svc data disable` + `svc wifi disable`，再重新挂载通知页；失败态精确显示“连不上服务器 / 检查网络连接后重试”和“重试”，未出现 `fetch failed`、`java.io.IOException` 或其他底层异常。取证后已立即执行 `svc wifi enable` + `svc data enable`，并确认 `Wi-Fi is enabled`。证据：`/private/tmp/m4-rf4-offline.png`，控件树 `/sdcard/m4-rf4-online.xml`、`/sdcard/m4-rf4-offline.xml`。
- ✅ **R-G8 同图重复保存去重** — 在 `tid=47337312` 查看器对同一张图片连续点两次“保存到相册”，`Pictures/NGA` 文件数为 `0 → 1 → 1`；第一次 toast 为“已保存到 相册/NGA”，第二次为“这张图已经在 相册/NGA 里了”，没有生成重复文件。只写入模拟器本地相册，不涉及论坛/账号状态；当前保留 1 张测试图。证据：`/private/tmp/m4-rg8-first.png`、`/private/tmp/m4-rg8-second.png`，控件树 `/sdcard/m4-rg8-viewer.xml`。
- ✅ **R-I5 抽屉头像进入当前账号资料页** — 抽屉显示当前账号 `lemon43(67296151)`，头像控件语义为“查看我的资料”；点击后进入标题为“用户资料”的页面，显示用户名 `lemon43`、用户 ID `67296151` 与基础信息/签名区，不是账号管理页。全程未点“修改签名”等编辑入口。证据：`/private/tmp/m4-ri5-profile.png`，控件树 `/sdcard/m4-ri5-drawer.xml`、`/sdcard/m4-ri5-profile.xml`。
- ✅ **主题列表甩滚定性（白块/错位）** — `网事杂谈`主题列表连续执行 7 次快速上下甩滚，录屏共 572 帧；抽查覆盖完整上下行程的 15 个时点，列表行始终连续，未见白块、内容重叠或错位。⏭️ 卡顿快慢/帧率体感按要求留真机轮，不以模拟器定性。证据：`/private/tmp/m4-topic-scroll.mp4`、逐帧目录 `/private/tmp/m4-topic-scroll-frames/`、索引 `/private/tmp/m4-topic-scroll.csv`，滚动前后控件树 `/sdcard/m4-scroll-before.xml`、`/sdcard/m4-scroll-after.xml`。
- ✅ **A2 自适应图标裁切** — Pixel API 37 的 Home screen Icons 面板提供 `Circle / Square / 4-sided cookie / 7-sided cookie / Arch`；实际 Apply 并在主屏核对 `Circle`、`Square`、`4-sided cookie` 三档，NGA 图标随 mask 正确变形，白色 `NG` 字标与斜纹背景均完整，无裁字、溢出或空白。形状已恢复原始 `Circle`；为继续 A3 暂留一个主屏图标，A3 后移除。证据：`/private/tmp/m4-a2-circle-restored-home-icon.png`、`/private/tmp/m4-a2-square-home-icon.png`、`/private/tmp/m4-a2-cookie-home-icon.png`，控件树 `/sdcard/m4-a2-shapes.xml`、`/sdcard/m4-a2-square-home-icon.xml`、`/sdcard/m4-a2-cookie-home-icon.xml`。
- ✅ **A3 主题图标 / monochrome 图层** — Android 17 将原“主题图标”开关收进 Home screen Icons → Style 的 `Default / Minimal`。实际 Apply `Minimal` 后，主屏 NGA 图标由绿底白字切为壁纸取色的浅蓝底/深蓝 `NG`，字标轮廓完整，不是空白方块；恢复 `Default` 后回到绿底白 `NG`。测试用主屏快捷方式已通过 Launcher 的 `Remove` 移除（不是卸载），原始 `Circle` 形状也已恢复。证据：`/private/tmp/m4-a3-minimal-preview.png`、`/private/tmp/m4-a3-minimal-home.png`、`/private/tmp/m4-a3-default-restored.png`、`/private/tmp/m4-home-restored-no-ng.png`，控件树 `/sdcard/m4-a3-minimal-selected.xml`、`/sdcard/m4-a3-minimal-home.xml`、`/sdcard/m4-home-restored-no-ng.xml`。
- ⚠️ **G3 捏合缩放** — API 37 的 `adb shell input motionevent` 仅接受单个 `x y`，不能指定 pointer id；`dumpsys input` 确认主屏 `/dev/input/event1` 支持 11 个 MT slots，但 shell 向其写入双 slot `sendevent` 被 SELinux 全部拒绝（`Permission denied`），没有产生有效双指手势。继续需要将 adbd 提权为 root，未为此扩大系统权限；因此焦点钉在两指中点、上下限回弹仍无法可靠判定，按清单保留 ⚠️。证据：`input help`/`getevent -pl`/`dumpsys input` 终端输出、无效注入录屏 `/private/tmp/m4-g3-pinch-out.mp4`。

### 环境还原确认

- ✅ 系统已恢复浅色档（`Night mode: no`）；字号保持原值 `font_scale=1.0`。
- ✅ Wi-Fi 已恢复（`Wi-Fi is enabled`），移动数据开关为 `mobile_data=1`；Metro reverse 仍有 `tcp:8082 → tcp:8082`。
- ✅ Pixel 图标样式已恢复 `Default`、形状已恢复 `Circle`，测试临时添加的主屏 NGA 快捷方式已移除；`theme_customization_overlay_packages=null`。
- ✅ 图片策略全程未改；`Pictures/NGA` 保留本轮 R-G8 产生的 1 张测试图作为证据。
- ✅ 未退出登录、未执行 `pm clear`、未修改签名/资料、未发帖回帖、未点赞、未收藏或取消收藏；当前账号仍为 `lemon43(67296151)`，app 已回到首页且抽屉打开。证据：`/private/tmp/m4-final-state.png`、控件树 `/sdcard/m4-final-state.xml`。

## A5 release 包复验(codex,2026-08-12)

- ✅ **A5-1 深色冷启动屏（release）** — 已安装包 `com.chasel.ng2`（`lastUpdateTime=2026-08-12 22:46:04`）与本机 22:45 产出的 `android/app/build/outputs/apk/release/app-release.apk` 对应；执行 `cmd uimode night yes` → `am force-stop com.chasel.ng2` → `monkey -p com.chasel.ng2 -c android.intent.category.LAUNCHER 1`。逐个检查录屏的全部解码帧：帧 70–73 是系统 Launcher 向 app 启动窗的深色过渡，帧 74 起为深色底 + 绿色 `NG` 的稳定启动屏，未出现浅色 `#FCF4E1` 帧或纯黑帧；帧 86–91 启动屏在已经画好的深色 RN 首页上淡出，帧 92 起首页完整可见，启动屏没有早收。根因类型明确：本轮既没有“启动屏图片/底色本身错色”，也没有“启动屏收起后到 RN 首帧之间露出全局浅色 `windowBackground`”；上一轮 dev launcher 的奶油色加载层在 release 包中不存在。证据：`/private/tmp/m4-a5-release-dark-valid.mp4`、逐帧颜色索引 `/private/tmp/m4-a5-release-dark-valid.csv`、关键帧目录 `/private/tmp/m4-a5-release-dark-valid-frames/`（`f-0069.png`、`f-0070.png`–`f-0074.png`、`f-0085.png`–`f-0093.png`）。
- ✅ **A5-2 浅色冷启动屏（release）** — 执行 `cmd uimode night no` → `am force-stop com.chasel.ng2` → 同一 `monkey` Launcher 冷启动。逐个检查全部解码帧：帧 102–106 是系统 Launcher 向 app 启动窗的合成过渡，未出现整屏黑帧；帧 107–109 为稳定的奶油色启动屏 + 浅色版较深绿色 `NG`，录屏 YUV 解码主色约为 `(250,243,223)`，与源色 `#FCF4E1` 的压缩偏差一致；帧 111–113 启动屏在已经画出顶栏、标签、提示条及内容加载态的浅色 RN 首页上淡出，帧 114 起完整首屏可见，没有启动屏早收或空底窗口。上一轮 dev launcher → MainActivity 的黑帧及 `Tools` 空奶油层在 release 包中均未出现。证据：`/private/tmp/m4-a5-release-light-valid.mp4`、逐帧颜色索引 `/private/tmp/m4-a5-release-light-valid.csv`、关键帧目录 `/private/tmp/m4-a5-release-light-valid-frames/`（`f-0101.png`–`f-0109.png`、`f-0111.png`–`f-0115.png`）。

### 环境还原确认

- ✅ A5-2 完成后再次执行 `cmd uimode night no` 并确认 `Night mode: no`；系统夜间模式已还原并保持浅色。
- ✅ 未卸载、未执行 `pm clear`、未退出登录，也未修改任何服务端或账号状态；已安装包仍为 `com.chasel.ng2` release（`versionName=0.1.0`、`versionCode=2`、`lastUpdateTime=2026-08-12 22:46:04`），最终 `topResumedActivity=com.chasel.ng2/.MainActivity`。

## 本轮收口(派发方 Claude,2026-08-12)

- **A5 最终判定 ✅**:dev client 轮的两个 ❌ 是环境噪声(expo-dev-launcher 的奶油色 bundle 加载层 +
  dev launcher → MainActivity 的 Activity 切换黑帧,release 包里这个 Activity 不存在)。
  release 包抽帧已由派发方复核关键帧:深色轮 `f-0074` 是 `#1C1C1B` 底 + 墨绿 NG,
  `f-0088` 是启动屏在**已经画好的深色首页**上淡出(顶栏/标签/公告条/「正在载入我的收藏…」都在),
  既没有错色帧也没有早收。`android.backgroundColor` 缺 values-night 版本这个隐患本轮没有暴露成缺陷
  (启动屏一直盖到 RN 首帧),**不改**。
- **模拟器轮结论**:14 项通过(R-G1/R-H5/R-D4/R-E7/R-F4/R-G8/R-I5、甩滚无白块错位、A2 三档 mask、
  A3 monochrome 取色、A5-1、A5-2),0 缺陷,无代码改动。
- **留真机轮**:① G3 双指捏合(API 37 的 `input motionevent` 不能指定 pointer id,`sendevent` 双指注入被
  SELinux 拒,要提权 adbd;真机上人工两指最快);② 120Hz 滚动流畅度的 release 对拍
  (`m4-fix-verification.md` B 节的假设 1「dev bundle 开销」需要在真机 + release 包上复测)。
- **环境**:验完已把 dev client(`app-debug.apk`)装回模拟器,`adb reverse tcp:8082` 已恢复,
  登录态(lemon43)全程保留——release 的 signingConfig 用的就是 debug keystore,签名相同,
  覆盖安装不丢数据,这条以后本地验 release 行为可以直接复用。
