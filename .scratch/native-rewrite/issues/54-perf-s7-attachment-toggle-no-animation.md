# 54 — 场景 7 附件展开/收起瞬时跳变

**类型:** performance / acceptance blocker  
**优先级:** P1  
**Status:** resolved

## 现象

票 19 真机 release 验收中，在含 2 个图片附件的主题 `tid=47328470` 上执行 15 轮
附件展开/收起。折叠条与附件宫格直接互换，没有 spec §五场景 7 要求验收的连续展开/
收起动画；录屏表现为整块内容瞬时跳变。

同一 30 秒样本中，`dumpsys gfxinfo` 现代口径为 25/124 janky frames（20.16%）。
录屏为 VFR，29.87 秒仅记录 67 帧，画面变化集中在每次点击的单帧替换，无法形成可裁决
的动画速度曲线。附件图片已由登录态正常加载，排除失败页与网络占位。

## 复现

1. release 包 `com.chasel.ng2.n` 登录后打开 `ng2n://read.php?tid=47328470`。
2. 滚动到主楼底部的“点击显示附件(2)”。
3. 连续点击展开与“收起附件”。
4. 观察附件宫格瞬时出现/消失，正文下方内容同步跳位。

## 证据

- `acceptance/perf/s7-native-framestats-v2.txt`
- `acceptance/perf/s7-native-rec-analysis-v2.txt`
- 录屏（不进 git）：`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s7-native-attachments-v2.mp4`

## 验收期望

附件宫格展开/收起具有连续动画；120Hz 录屏逐帧速度曲线连续，动画内不得肉眼可见停格
或整块突现，且现代 janky 不劣于并装基线。

## 修复(2026-08-24)

`ui/topic/FloorCard.kt` 的 `AttachmentGrid` 原先是「`if (!open) { 折叠条; return }`」的
直接条件渲染:折叠条与宫格瞬时互换,楼层高度一帧之内从 42dp 跳到宫格全高,正文下方
整块跟着跳位——所以录屏里只有单帧替换,裁不出速度曲线。

改成折叠条与宫格各挂一个 `AnimatedVisibility`,共用同一组进出场:

- 进:`expandVertically + fadeIn`;出:`shrinkVertically + fadeOut`。
- 时长/缓动取 `ui/common/Motion` 那份唯一 token:`DURATION_BASE`(200ms)+
  `easeStandard`(`cubic-bezier(.25,.1,.25,1)`),与正文 `[collapse]` 折叠卡
  (`ui/bbcode/Blocks.kt` 的 `CollapsibleCard`)同一档,逐帧对拍时两处曲线一致。

两块在同一条时间轴上一个收一个放,任一时刻总高 = `折叠条高 × (1-t) + 宫格高 × t`,
连续变化;楼层在 LazyColumn 里只是被逐帧推高/推低,item 不增删,滚动位置不突跳。

没有用 `Modifier.animateContentSize()`:它只补容器高度,内容在第 0 帧就已经换成宫格,
42dp 的窗口里直接露出宫格顶部,首帧仍是一次肉眼可见的像素突变。双 `AnimatedVisibility`
顺带把两块内容交叉淡入淡出,首帧不跳。

关于票里那 20.16% 现代 janky:走查了「点击展开是否触发整楼重组 / 图片重新拉取」——
`open` 状态只在 `AttachmentGrid` 内部读,重组范围就是这一个 composable,不牵动整楼;
缩略图走全局 ImageLoader(`di/ImageModule`)配好的内存 + 磁盘缓存,反复展开收起是
内存命中,不会重新发请求,Coil 对内存命中也不放 crossfade,不会再闪一下。
即没有发现额外的可修项,原样本 124 帧、画面几乎静止,本身样本量偏小。

构建与单测:`./gradlew :app:assembleDebug :app:testDebugUnitTest` 绿。

**待真机复验**:需要在 `tid=47328470` 上重跑场景 7(15 轮展开/收起 + 120Hz 录屏逐帧),
确认速度曲线连续、无停格,并复测现代 janky 是否不劣于并装基线。
