# 57 — P1:连续快甩时滚动速度塌陷/停滞

**Status:** verified（三轮修复终轮真机复验通过）

**Severity:** P1（用户可感知，主题列表稳定复现；楼层流可放大成数百毫秒停滞）

## 现象

`com.chasel.ng2.n` release 包在短间隔连续快甩时，列表仍处于滚动状态，但内容速度会
突然从高速降到接近静止，约 0.2 秒后由下一次手势重新拉起；楼层流的一轮进一步出现
约 0.43–0.56 秒的静止/近静止段。肉眼表现与用户报告一致：不是常规 fling 平滑减速，
而是速度在连续输入中突然塌掉。

本轮先把系统熄屏超时设为 600000ms，前台始终为
`com.chasel.ng2.n/com.chasel.ng2n.MainActivity`。设备为 `pudding / 25113PN0EC`，
Android 16，包为 release 0.1.0(1)、`speed-profile`。无线 ADB 端口本轮由 mDNS 确认
轮换为 `192.168.0.101:41641`。

## 复现脚本

### 固定节奏

```bash
adb -s 192.168.0.101:41641 shell settings put system screen_off_timeout 600000
for i in $(seq 1 10); do
  adb -s 192.168.0.101:41641 shell input swipe 610 2100 610 550 100
  sleep 0.25
done
```

楼层流补了一轮 8 次同脚本，避免过早到达页尾。

### 节奏变体（模拟手动连甩）

```bash
# 3 次极快 → 停 650ms → 4 次稍长 → 停 450ms → 3 次极快
for i in 1 2 3; do input swipe 610 2100 610 550 100; sleep 0.22; done
sleep 0.65
for i in 1 2 3 4; do input swipe 610 2100 610 550 160; sleep 0.20; done
sleep 0.45
for i in 1 2 3; do input swipe 610 2100 610 550 100; sleep 0.30; done
```

每轮同步采 `screenrecord`、`dumpsys SurfaceFlinger` 的 frameRateOverride 序列、
`dumpsys gfxinfo ... framestats` 与进程 logcat。录屏正文区域以逐帧相位相关计算纵向
位移，100ms 分桶得到速度曲线；脚本中明确的 650/450ms 停顿不计作复现。

## 复现率

| 场景/样本 | 脚本 | 结果 | 关键现象 |
|---|---|---|---|
| 主题列表 fixed-1 | 10×100ms / 250ms | 复现 | 高速 13k–16k px/s 间出现约 0.2s 的 0.48k px/s 段 |
| 主题列表 fixed-2 | 同上 | 复现 | 13k–17k → 0.57k/近 0 → 18k px/s |
| 主题列表 rhythm-1 | 节奏变体 | 复现 | 排除计划停顿后，第三组手势内仍出现约 0.2s 近静止 |
| 主题列表 fixed-3 | 8×100ms / 250ms | 复现 | 三次 0.1–0.2s 速度塌陷；实时 logcat 无 GC |
| 楼层流 fixed-1 | 10×100ms / 250ms | 复现 | 运动窗口出现 117.6ms 无新内容帧 |
| 楼层流 fixed-2 | 8×100ms / 250ms | 复现 | 14.2k → 2.66k → 静止约 0.43–0.56s → 15k+ |
| 楼层流 rhythm-1 | 节奏变体 | 未计复现 | 只观察到计划停顿与正常衰减 |

主题列表 **4/4**，楼层流 **2/3**，合计 **6/7（85.7%）**。

## 逐帧证据

### 主题列表 fixed-2（基准帧间隔 8.33ms）

| 帧 | 时间 | dt | 位移/速度 | 说明 |
|---:|---:|---:|---:|---|
| 74 | 1.491s | 8.84ms | 116px / 13.1k px/s | 正常高速 |
| 75 | 1.500s | 8.74ms | 36px / 4.12k px/s | 速度突然下降 |
| 76 | 1.509s | 9.26ms | 8px / 0.86k px/s | 仍在滚，但极慢 |
| 77 | 1.516s | 7.07ms | 4px / 0.57k px/s | 继续塌陷 |
| 78–90 | 1.524–1.624s | 约 8.3ms | 0px | 连续送帧但内容不动 |
| 91–97 | 1.633–1.691s | 约 8.3ms | 约 4px / 0.47–0.51k px/s | 近静止慢滚 |
| 104 | 1.749s | 8.24ms | 188px / 22.8k px/s | 下一次输入立即恢复高速 |

这不是普通 fling 尾段：塌陷发生在连续手势中间，前后都立刻回到 13k–23k px/s。

### 楼层流 fixed-2（基准帧间隔 8.33ms）

| 帧 | 时间 | dt | 位移/速度 | 说明 |
|---:|---:|---:|---:|---|
| 192 | 2.596s | 7.33ms | 104px / 14.2k px/s | 正常高速 |
| 193 | 2.605s | 9.02ms | 24px / 2.66k px/s | 突然减速 |
| 194 | 2.731s | **125.9ms** | 68px | 首个长空洞 |
| 195–226 | 2.739–3.037s | 持续送帧 | 0px | 内容静止约 0.30s |
| 227 | 3.163s | **125.7ms** | 160px | 第二个长空洞后恢复 |
| 228–233 | 3.171–3.210s | 约 8.3ms | 15.1k–20.5k px/s | 立即恢复高速 |

从降速帧 193 到恢复帧 228 约 0.57s，其中约 0.43s 为连续静止/近静止画面。

## 刷新率与帧耗时

- 采样前已将 `screen_off_timeout` 设为 600000ms，排除屏幕变暗锁 60Hz。
- 所有覆盖手势/停滞区间的 SurfaceFlinger 样本都为
  `(uid, frameRate)={10375, 120.00 Hz}`；滚动结束后才回到 `none`，**未出现 60Hz**。
- 主题列表四轮现代 janky 为 0.06%–0.08%，楼层流三轮为 0.09%–0.17%；
  `Number Missed Vsync` 均为 0。典型 p95 为 9–17ms。
- 因此不是显示刷新率降档，也不像持续的 UI/GPU 帧耗时超预算；C2 汇总几乎全绿，
  但 C1 位移时间轴明确看到内容速度状态塌陷。

## GC / 日志

- 主题列表 fixed-3 使用 `logcat -T 1 --pid=<ng2n pid>` 实时采样，复现三次速度塌陷，
  **没有 GC 记录**，故 GC 不是主题列表的必要条件。
- 楼层流 fixed-1/fixed-2 的精确窗口内分别出现一次 sticky/non-sticky GC，并伴随多次
  `SkJpegCodec` 解码；fixed-2 的 non-sticky GC 为 22:13:35.248，位于停滞窗口附近。
- ART 日志只报 GC 类型，没有给出 pause 时长，现有证据不足以把该次 GC 定为直接根因。
- 结论：GC/图片解码可能把楼层流的速度塌陷放大成更硬的停滞，但不能解释主题列表
  4/4 复现，也不是共同根因。

## 初步归因

优先怀疑**连续手势下 fling → 新 drag → fling 的速度交接/取消**：主题列表和楼层流
都使用 stock Compose `LazyColumn`，并都包在 `PullToRefreshBox` 中；短间隔的新 touch-down
会取消尚未结束的 fling。本轮时间轴呈现“高速 → residual velocity 接近 0 → 下一次
手势立即恢复高速”，更像 velocity/handoff 状态被清零，而不是渲染不动或刷新率下降。
`adb input swipe` 的离散 down/move 时序可能放大该窗口，修复复测还应补一轮真实手指
同节奏录屏或 InputDispatcher/Perfetto 事件时间线，确认不是注入器特有量化；但用户的
手动报告与两类列表上的现场形态一致，不能把本轮稳定复现直接判作注入器噪声。

楼层流另有次要放大因素：快速装入楼层卡和图片解码/GC，能把近零速度段放大成
117–126ms 的单次无新内容帧，并连续形成约 0.43s 静止段。修复定位建议先在共同的
LazyColumn/PullToRefresh 输入路径
记录每次 drag start/stop、fling 初始速度和取消原因，再单独验证禁用/替换 pull-refresh
nested-scroll 后是否消失；楼层流随后再隔离图片解码/GC。

## 证据

原始数据位于 `.scratch/native-rewrite/acceptance/perf/`：

- `t57-topic-{fixed-1,fixed-2,fixed-3,rhythm-1}-framestats.txt`
- `t57-floor-{fixed-1,fixed-2,rhythm-1}-framestats.txt`
- 同名前缀的 `-sf.txt`、`-logcat.txt`、`-rec.txt`、`-phase.csv`、
  `-phase-summary.txt`

录屏不进 git，位于：

```text
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-topic-fixed-1.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-topic-fixed-2.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-topic-rhythm-1.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-topic-fixed-3.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-floor-fixed-1.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-floor-fixed-2.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-floor-rhythm-1.mp4
```

## 验收期望

在 8–10 次、100ms swipe、200–300ms 间隔的连续输入下，逐帧速度曲线允许正常衰减，
但不得在下一次输入前后突然降到前一高速段的 10% 以下；不得出现 >100ms 无新内容帧。
滚动期间保持 120Hz，且修复不回退票 19 场景 3/4 的 janky 基线。

## 对拍(2026-08-24)

### 同一固定节奏脚本

在 RN `com.chasel.ng2` 的“网事杂谈”主题列表复用本票完全相同的 10 次
`100ms swipe + 250ms 间隔`脚本。录屏共 641 帧/6.30s，基准 8.36ms；相位速度
p10/p50/p90 为 7.59k/11.17k/17.55k px/s。三段有效运动窗口内没有原生那种
“高速 → 0–0.5k → 下一手恢复高速”的塌陷，现代 janky 为 1/1,280(0.08%)，
missed-vsync 0。

| 包 | 同脚本有效性 | 逐帧速度结果 | 裁决 |
|---|---|---|---|
| 原生 `com.chasel.ng2.n` | 有效 | 主题列表 4/4 塌陷；fixed-2 有连续 13 帧 0px、随后约 0.5k px/s | **复现** |
| RN `com.chasel.ng2` | 有效 | 0 次同型塌陷；每次手势后平滑衰减并由下一手重新抬速 | **未复现** |
| anzong `gov.anzong.androidnga` | 无效 | “网事杂谈”持续“加载失败，请重试！”，未拿失败页冒充主题列表样本 | 不裁决 |

RN 在同一设备、同一 `adb input swipe` 注入节奏下未复现，因此可排除“三包共有的
注入器/系统量化”解释。anzong 本轮受 NGA 链路限制，不能作为有效反证。

### 原生真手连甩

所有者在同一原生 release 包上直接连续快甩，并现场确认：**按手指幅度本应大幅移动，
实际只小幅移动，有很明显的滑不动感**。录屏相位曲线在约 28.44–28.81s 连续落到
2.2k–5.7k px/s，明显低于该样本常见的 8k–16k px/s；约 28.82s 后下一手又回到
8k–14k px/s。该段方向连续，不是脚本预置停顿，故记为真手复现，也证明现象并非
`adb input swipe` 特有。

同期 SurfaceFlinger 活跃样本全部为 UID 10375 / 120Hz，没有掉到 60Hz；gfxinfo
现代 janky 3/1,282(0.23%)、missed-vsync 0，logcat 没有 GC 暂停证据。真手样本仍呈现
“有帧、120Hz，但位移速度塌掉”，继续支持 fling/drag 速度交接方向，而不是刷新率、
持续帧耗时或 GC。

### 对拍结论

结论为 **native-only（在有效可比包中）**：原生固定注入稳定复现且真手也复现；RN
同脚本未复现，已否定全包共有。anzong 因主题列表网络失败保持“不可用”，不对其作
阴性推断。

新增证据位于 `.scratch/native-rewrite/acceptance/perf/`：

- `t57-rn-topic-fixed-{framestats,phase-summary,phase,rec}.txt/csv`
- `t57-native-manual-{framestats,logcat,phase-summary,phase,rec,sf}.txt/csv`

对应录屏（不进 git）：

```text
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-rn-topic-fixed.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-native-manual.mp4
```
## 修复(2026-08-24)

### 复盘:先把「渲染/内容耗尽」两条排掉

拿本票留下的录屏重做逐帧,把 `t57-topic-fixed-3` 的 `-phase.csv` 与同轮
`-logcat.txt` 里的 MIUIInput DOWN/UP 时间轴对齐(以运动窗口起点对 DOWN1/3/5/7,
四点误差 ≤5ms,δ=0.922s),得到比票面更细的形态:

| 段 | 帧 | 形态 |
|---|---|---|
| drag2 | 55–66 | 逐帧 128px 恒定(= 注入 12 个 MOVE × 129px) |
| UP2 | 67 | dy 0 |
| fling2 | 68–81 | 124→116px,**与 fling7/fling8 起手完全同形**(spline 前段本来就平) |
| 塌陷 | 82–85 | 32 / 40 / 12 / 4px,约 33ms 掉到 ~0.5k px/s |
| 尾巴 | 86–106 | 帧间灰度差 3.2(全静止帧是 0.016、4px/帧是 2.2),约 170ms 的亚像素蠕动后停死 |

两条结论:

1. **不是内容耗尽**。塌陷当帧截图(`ffmpeg -ss 1.72`)是版块列表正中段,底部那一行
   被屏幕边缘切断;真到底时 `contentPadding(bottom=70.dp)` 会让最后一行整行留白,
   `LoadingFooter` 也会在场。四轮里三处塌陷都这样。
2. **fling 确实跑起来了、又被砍断**。fling2 只跑了约 1.8k px 就停,而同速的 fling8
   完整跑了 1.7s/约 9k px —— 与 15k px/s 的 spline 理论值(约 10.1k px、1.94s)吻合。

### 根因一:楼层流 = 到底自动翻页打在 fling 中段(实锤)

`t57-floor-fixed-2` 停滞窗口两端截图直接对上:

- t=2.58s 页码条高亮 **4**;
- t=2.90s 页码条高亮 **5**。

链路是 `EndReachedReporter`(footer 一进视口就算到底)→ `TopicViewModel.onReachedEnd()`
→ `goToPage(page + 1)` → `TopicPager` 的 `LaunchedEffect(vm.page)` → **`pagerState.scrollToPage()`**。
`scrollToPage` 是瞬时换页:新的一页是另一棵子树、另一个 `LazyListState`、纵向偏移
从 0 开始,当前这一把的纵向动量当场丢光,再叠上新页楼层组合 + `SkJpegCodec` 解码 + GC,
就是票面记的 117–126ms 无新内容帧 + 约 0.43s 静止。票面把 GC/解码当「放大因素」是对的,
但**触发器是翻页本身**,不是 GC。

### 根因二:`PullToRefreshBox` 的 nested-scroll 节点挡在每一次 fling 前面

两屏共有的那一层。material3 1.4 `PullToRefresh.kt`:

```kotlin
override suspend fun onPreFling(available: Velocity): Velocity =
  Velocity(0f, onRelease(available.y))

private suspend fun onRelease(velocity: Float): Float {
  ...
  animateToHidden()          // 无条件 await,不看 distancePulled、也不看 enabled
  ...
}

override fun onPostScroll(consumed, available, source): Offset = when {
  source == NestedScrollSource.UserInput -> {         // 注释写「Swiping down」但没判方向
    ...
    coroutineScope.launch { if (!state.isAnimating) state.snapTo(verticalOffset / thresholdPx) }
    ...
  }
}
```

而 `Scrollable.kt` 的 `ScrollingLogic.onScrollStopped` 是
`dispatchPreFling(velocity)` 拿到结果**之后**才 `doFlingAnimation(available)`。于是:

1. 哪怕 `distanceFraction` 全程 0、`animateTo(0f)` 时长算出来是 0,`Animatable.animateTo`
   也要先 `withFrameNanos` 挂一帧 —— 120Hz 上每次松手白等 8.3ms 才起 fling
   (录屏里 UP→首个 fling 帧的 18–30ms 里就有这一份);
2. 它跑在 `Animatable` 的 `MutatorMutex` 上,而同一个 state 的 `snapTo` 被 `onPostScroll`
   **每个滚动帧**都 `launch` 一发(向上滚也发)。排在后面的 `snapTo` 只要落在 `animateTo`
   拿到 mutex 之后,就以 `MutationInterruptedException`(`CancellationException` 的子类)
   把它掐掉;`Animatable.runAnimation` 原样往外抛 → `onRelease` → `onPreFling` →
   `NestedScrollNode.onPreFling` → `dispatchPreFling` → 冒到 `onScrollStopped`,
   **整个 fling 协程当场取消,`doFlingAnimation` 一次都没跑**。全程无日志、不算 jank、
   不掉帧、不降刷新率 —— 与票面「C2 全绿、C1 塌陷」的组合完全一致。

`NestedScrollNode.onPreFling` 是 `parentConnection?.onPreFling(available)` 先行(外层优先),
所以在列表和 `PullToRefreshBox` 之间再插一个 connection 挡不住;`enabled = false` 也不行,
`onRelease` 根本不读 `enabled`。

### 改动

- 新增 `ui/common/ListPullToRefresh.kt`:自实现 `PullToRefreshState`
  (material3 把它开成了 `PullToRefreshBox(state = ...)` 的公开参数),
  `animateToHidden()` / `snapTo()` 在「本来就归零 / 值没变且没有动画在跑」时**同步返回**,
  不碰 `Animatable`、不碰 `MutatorMutex`。真下拉过时行为与默认实现一字不差。
  判据抽成 `pullToRefreshNeedsHide` / `pullToRefreshNeedsSnap` 两个纯函数,配
  `ui/common/ListPullToRefreshTest.kt`(6 例)。
- 全部 8 处 `PullToRefreshBox` 调用点(版块列表、楼层流、热帖/主题列表、收藏夹、
  收藏主题、他的主题、过滤词)统一传 `state = rememberListPullToRefreshState()`。
- `ui/topic/TopicScreen.kt` 的到底判据加一条「这一把已经滚停」:
  新的纯函数 `shouldTurnPageAtEnd(lastVisibleIndex, totalItemsCount, scrolling)`,
  接 `listState.isScrollInProgress`。fling 能完整跑到本页页尾,换页发生在静止态;
  手指还按着时同理,抬手落定再翻。配 `ui/topic/EndReachedTest.kt`(4 例)。

### 为什么能消塌陷

- 楼层流那条:换页不再打断 fling,0.43–0.56s 的静止段没有了触发器;
  新页的组合与图片解码也移到静止态,117–126ms 的无新内容帧同样落在静止态里
  (静止态出帧空洞按 C10 判据本来就不计缺陷)。
- 两屏共有那条:`onPreFling` 不再挂帧、也不再有可被取消的挂起点,
  「fling 协程被 `MutationInterruptedException` 静默取消 ⇒ 松手即停」这条路彻底断掉;
  顺带每个滚动帧少一次 `launch` + `MutatorMutex.mutate`。

### 还没定死的部分(留给真机复验)

用同一台设备、同一脚本录的 RN 版对照(`t57-rn-topic-fixed.mp4`,LegendList + 原生
ScrollView 的 fling,与 Compose 毫无共享代码)拿同一套帧间灰度差跑出来,**同样有两处
约 100–200ms 的速度塌陷**(1.85–1.95s、2.65–2.80s;塌陷段 diff 3.5–5,高速段 18–20),
形态与 native 的三处(1.65–1.78、2.55–2.72、3.50–3.58)一个量级。也就是说主题列表的
残余塌陷**未必全部来自 app 代码**:`adb input swipe` 的注入时序(本轮 framestats
`Number High input latency` 高达 2020)在两个栈上都能压出同一形态。

本票的两处改动都是有代码实锤的真缺陷,先修;复验时按验收期望重跑:

- 同脚本下逐帧速度不得掉到前一高速段的 10% 以下;
- 不得出现 >100ms 无新内容帧;
- 滚动期间保持 120Hz;
- 不回退票 19 场景 3/4 的 janky 基线。

若主题列表仍残留同量级塌陷,请把 RN 版同轮对照一起交上来 —— 两边同时残留即判为
注入器/输入链量化,不再往 app 代码里追。

**待真机复验。**

## 二轮修复合并复验(2026-08-27)

在真机 `pudding / 25113PN0EC`、Android 16、release/`speed-profile` 上复验。APK
MD5 为 `2c985a568f20326ff44aae592edcd02d`，设备为
`adb-5321a265-YQpTd2._adb-tls-connect._tcp`；屏幕全程亮屏，录屏基准 120Hz，前后焦点
均为 `com.chasel.ng2.n/com.chasel.ng2n.MainActivity`。当前已登录，主题列表使用原来的
`fid=-7`「网事杂谈」，不是失败页。

两条固定节奏脚本原样各跑一轮：主题列表 10 次、楼层流 8 次
`input swipe 610 2100 610 550 100`，每次间隔 250ms。

| 样本 | 速度/内容连续性 | FrameTimeline | 裁决 |
|---|---|---|---|
| 主题列表 | 分页边界仍有 108–283ms 内容静止窗；一处前段约 14.4k px/s 后降到 0.94k px/s（**6.5%**） | 0/1,508 janky（0.00%），missed-vsync 0，p95 6ms | **不过 10% 与 100ms 两闸** |
| 楼层流 | 脚本输入段无同型 10% 塌陷，但 page 切换处录屏 dt **184.0ms**，没有形成期望的 220ms 连续动画帧 | 1/1,170 janky（0.09%），missed-vsync 0，p95 7ms | **不过 100ms 闸** |

场景 3/4 的 janky 没有回归：主题列表 0.00% 不差于原 0.02%，楼层流 0.09% 不差于
原快甩 0.43% / RN 历史 2.7%。但 C1 优先于 C2，二轮修复仍未满足本票两个硬闸，故
保持 reopened。现象已从一轮的 EdgeEffect 蠕动/瞬时换页，变成分页边界的明确静止窗；
若继续定位，应先查距离预取为何仍约每两手耗尽，以及相邻页 `animateScrollToPage` 为何
在录屏上仍留下 184ms 无新帧。

证据：`acceptance/perf/t57-{topic,floor}-r2-{framestats,framestats-analysis,phase-summary,phase,rec}.txt/csv`；
录屏（不进 git）：`/tmp/t57-{topic,floor}-r2.mp4`。

## 修复后真机复验(2026-08-24)

复验包为 release APK `04fffa7d032e61d6…`，设备 dexopt 为
`speed-profile / reason=baseline`。设备本地账号状态在新包上显示“未登录”，所以
“网事杂谈”只得到服务端权限错误页并按 T6 作废；主题列表有效样本改用游客可访问、仍走
同一 `TopicListScreen` 的“艾泽拉斯议事厅”，手势、坐标、次数、间隔和逐帧相位口径均
与票面固定脚本相同。

### 主题列表：仍复现，不通过

10 次 `100ms swipe + 250ms` 的 7.33 秒录屏为 768 帧，基准 8.33ms(120.0Hz)。
第一次典型塌陷在 2.642s 的 14.81k px/s 后发生：

| 时间 | 位移/速度 | 说明 |
|---:|---:|---|
| 2.642s | 120px / 14.81k px/s | 前段高速 |
| 2.684s | 4px / 0.45k px/s | 降到前段的 **3.1%** |
| 2.692–2.725s | 连续 0px | 有帧但内容不动 |
| 2.734–2.842s | 约 0.46–0.53k px/s | 持续近静止 |
| 2.851–2.959s | 连续 0px | 下一手前仍未恢复 |

同轮 3.80–3.90s 与 4.70–4.90s 还有两处约 0.46–0.54k px/s 的同型塌陷，均低于
前一高速段的 10%。录屏运动窗口最大 dt 18.2ms，没有 >100ms 出帧空洞；现代 janky
1/1,534(0.07%)、missed-vsync 0，logcat 无 GC 暂停。即 C2/刷新节奏仍绿，但 C1 速度
闸明确未过。

### 楼层流：速度闸过，但无新内容帧闸仍不过

同一 `tid=47328470` 从 page 1 顶部执行 8 次固定手势；另做一轮不并发 SurfaceFlinger
采样的干净录屏，排除 `dumpsys` 对编码器的扰动。4.92 秒 / 407 帧、基准
8.32ms(120.2Hz)，有效速度 p10/p50/p90 为 10.88k/14.12k/17.01k px/s，最低有效
100ms 桶 5.70k px/s，没有掉到前段高速的 10% 以下。

但 page 3→4 的切换仍在固定脚本运动期内产生 **276.4ms** 无新内容帧：3.005s 仍为
1.93k px/s，3.012–3.037s 内容停止，下一帧直到 3.313s 才出现并立即恢复 26.15k px/s。
同轮末段另有 201.9ms 空洞。现代 janky 1/806(0.12%)、missed-vsync 0，仍不能替代
票面“不得出现 >100ms 无新内容帧”的 C1 闸。

### 回归项与裁决

- 场景 3 完整 25 次 `300ms swipe + 900ms` 复跑：1/6,306(0.02%)，与首轮
  1/6,318(0.02%) 相同，≤1%，无回退。
- 楼层固定节奏样本现代 janky 0.12%，不差于场景 4 原生快甩 0.43% / RN 历史 2.7%。
- 两个屏的 120Hz 录屏基准分别为 8.33ms / 8.32ms，排除熄屏降 60Hz。

证据新增于 `acceptance/perf/`：

- `t57-topic-verify-{framestats,logcat,phase-summary,phase,rec}.txt/csv`
- `t57-floor-verify-clean-{framestats,phase-summary,phase,rec}.txt/csv`
- `s3-native-regression-verify.txt`

录屏（不进 git）：

```text
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-topic-verify.mp4
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-floor-verify-clean.mp4
```

**复验不通过，票 57 reopened。** 主题列表仍违反 10% 速度闸，楼层流仍违反 100ms
无新内容帧闸；虽然场景 3/4 的现代 janky 均未回退，不能据此改判 verified。

## 富 trace(2026-08-24)

按 `s9-native-rich.cfg` 的同口径采两份 64MB ring-buffer trace：
`linux.ftrace` 含 sched/freq/binder 与 atrace `gfx/view/input/sched/freq/binder_driver`，
`atrace_apps=com.chasel.ng2.n`，同时启用 SurfaceFlinger FrameTimeline。设备内核没有
`/sys/kernel/tracing/events/input`，所以 input 证据来自 atrace 的逐条
`MotionEvent ACTION_*` slice；没有伪造不存在的 kernel input event。

### 主题列表：fling 已停，EdgeEffect 仍在送静态帧

游客可访问的“守望先锋”仍走同一个 `TopicListScreen`，执行票面 10 次固定节奏。
598 帧/6.959s 的录屏出现四处同型塌陷；首处从 3.772s 的 12.97k px/s 降到
3.789s 的 0.92k，随后为 0/约 0.47–0.53k px/s，下一手恢复到 19.88k px/s。
以首个 ACTION_MOVE 对首个有效运动帧，trace 比录屏晚 138.566ms；该塌陷对应 trace
3,928–4,121ms，上一 ACTION_UP 为 3,713.097ms，下一 ACTION_DOWN 为 4,121.904ms。

| TraceProcessor 指标 | 正常 fling 3,720–3,919ms | 塌陷 3,928–4,121ms |
|---|---:|---:|
| `Choreographer#doFrame` | 23，max 2.336ms | 23，max 0.808ms |
| `animation` / `Recomposer:animation` | 23 / 23 | **0 / 0** |
| `Compose:recompose` / measure-layout | 32 / 32 | **0 / 0** |
| UI traversal / draw | 23 / 22 | 23 / 23 |
| RenderThread Drawing | 23 | 24 |
| `AndroidEdgeEffectOverscrollEffect` | 0 | **24** |
| app surface actual frame | 23，0 jank | 23，0 jank |
| UI / RT Running | 43.354 / 38.957ms | 14.451 / 43.196ms |

最后一条 `Recomposer:animation` 在 3,919.177ms；从下一帧直到下一次 DOWN，fling 对应
的 Compose animation/recompose/measure 已全部消失，故 **Choreographer doFrame 里没有
fling animator 在跑**。但 ViewRoot traversal/draw 与 RenderThread 仍约 120Hz 工作，
塌陷窗 24 个 RT frame 全部在画 `AndroidEdgeEffectOverscrollEffect`，FrameTimeline 仍有
23 个 on-time app frame。也就是说“有帧但内容不动”的帧由 UI traversal + RT 边缘效果
产生，不是 LazyColumn 在推进 offset。

Perfetto 没有 `scroll`/`offset` counter，不能凭 trace 伪报 `LazyListState` 数值；录屏
相位的 0/约 4px 位移是 offset 未有效推进的直接观测，trace 的 animation/recompose
归零是状态侧旁证。窗口内 UI/RT 均无长任务、dequeueBuffer 等待或调度饥饿。归因因此
收敛为：**fling/scroll-state 提前终止，独立 EdgeEffect invalidation 继续送静态帧**；
不是刷新率、GC、渲染超时或 CPU 阻塞。

### 楼层流：翻页帧后两线程睡眠，不是 276ms 主线程大活

`tid=47328470` 执行 8 次固定节奏，579 帧/7.303s；page 3→4 处录屏下一内容帧
`dt=265.900ms`，与复验的 276.4ms 属同一类空洞。时钟偏移为 +130.572ms，关键时间线：

| trace 相对时间 | 事件 |
|---:|---|
| 3,599.957ms | 第 4 手 ACTION_UP |
| 3,632.023ms | 18.921ms doFrame：recompose 4.190ms、traversal 14.676ms、measure/layout 12.773ms |
| 3,651.504ms | 空洞前最后一个 app frame（Late Present / Buffer Stuffing） |
| 3,656.987–3,990.773ms | 无 app slice/doFrame/RT draw/app buffer；UI 睡眠 333.420/334ms，RT 睡眠 334/334ms |
| 3,991.677ms | 下一手 ACTION_DOWN |
| 4,005.483ms | 下一 app frame，之后恢复 on-time 帧 |

app surface 的完整无帧间隔为 353.979ms，录屏与其重叠的无新内容段为 265.900ms。
这不是单个 276ms UI task，也不是 RT/dequeueBuffer/GPU 背压：翻页只产生一个有界的
18.921ms 组合/布局峰，之后 UI 与 RT 都睡到下一次输入。trace 内没有主线程网络/binder
等待，也没有空洞内的重 Compose；当前数据源不能观察后台 HTTP syscall，因而不能否定
后台请求，但可以明确否定“主线程同步等网络/做 276ms 组合大活”。现象链为：**翻页交接
结束当前 fling/invalidations，新页成为静止画面，下一手才重新产帧**。

### 证据文件

```text
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-rich-topic.pb
  sha256 37591e38aa59cae4b591a44eb161e0f5def54aa58985bfd4c0f9479d9e8ff2f6
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/t57-rich-floor.pb
  sha256 79011f63df72814197b65ee976387a08473b05bf4b1459e5b5df9adbb0e1b496
```

对应 `t57-rich-{topic,floor}.mp4` 同目录不进 git；完整窗口、SQL 与计数固化在
`acceptance/perf/t57-rich-analysis.txt`。本轮进一步支持票 57 保持 **reopened**。

## 二轮修复(2026-08-24)

一轮的两处改动都是真缺陷、都保留;但**都不是这张票的根因**。二轮拿
`t57-rich-{topic,floor}.pb` 用 trace_processor 逐帧重查,把两屏的因果各自钉死了。

### 先给 `ListPullToRefresh` 摘帽(有实锤)

一轮把 material3 的 `PullToRefreshBox` 当主嫌。读 `PullToRefresh.kt`(1.4.0)与
`Scrollable.kt`(foundation 1.12.0)源码可以直接排掉它**在 fling 路径上**的嫌疑:

- `doFlingAnimation` 里滚动增量是以 `source = NestedScrollSource.SideEffect` 派发的,
  而 `PullToRefreshModifierNode.onPreScroll` / `onPostScroll` 两个 `when` 的消费分支
  都写死 `source == NestedScrollSource.UserInput` —— **fling 期间两个回调一律
  `Offset.Zero` 原样放行**,不存在「吞掉 available」;
- `onPreFling` → `onRelease` 在 `distancePulled == 0f` 时 `consumed = 0f`,也不吞速度;
- 一轮把 `animateToHidden()` / `snapTo()` 改成「本来就归零时同步返回」之后,
  连那条 `MutationInterruptedException` 取消整个 fling 协程的路也断掉了。

全仓 grep `nestedScroll` / `overscroll` / `NestedScrollConnection`:除
`ListPullToRefresh.kt` 的注释外**零命中** —— app 侧没有第二个 nested-scroll 或
overscroll 注册点。所以「中段 overscroll」不可能来自 app 自己挂的连接器。

### 根因(主题列表):fling 跑到**已加载内容**的末尾,余速被灌进 EdgeEffect

Compose 的既定链路,一环扣一环:

1. `LazyListState.onScroll` 第一行:`if (distance < 0 && !canScrollForward …) return 0f`;
2. `DefaultFlingBehavior.performFling` 的 `animateDecay` 回调:
   `if (abs(delta - consumed) > 0.5f) this.cancelAnimation()`,并把
   **没跑完的速度**当返回值交出去 —— 这就是富 trace 里「`Recomposer:animation` /
   `Compose:recompose` / measure 在塌陷窗内全部为 0」的原因:**fling animator 真的死了**;
3. `ScrollingLogic.onScrollStopped` 把它算进 `leftForOverscroll`;
4. `AndroidEdgeEffectOverscrollEffect.applyToFling` 末尾一发
   `getOrCreateBottomEffect().onAbsorbCompat(...)`,把这份速度**吸进 EdgeEffect**;
5. stretch 的回弹只改 `redrawSignal`(`mutableStateOf` + `neverEqualPolicy`,只在
   `StretchOverscrollNode.draw()` 里被读)——**只触发重绘,不触发重组/重测**。
   于是接下来约 430ms:UI traversal + RT 照常约 120Hz 出帧、FrameTimeline 23 帧全 on-time、
   janky 0,但列表一动不动。

这正好把富 trace 那张表的每一个数字解释干净:

| 指标 | 塌陷窗 3,928–4,121ms | 为什么 |
|---|---:|---|
| `Recomposer:animation` / `Compose:recompose` | 0 / 0 | fling animator 已被 `cancelAnimation()` |
| AndroidOwner measure/layout | 0 | 列表不再滚,不需要重测 |
| `AndroidEdgeEffectOverscrollEffect` RT 帧 | 24 | stretch 的 `onAbsorb` 回弹动画在跑 |
| UI traversal / draw | 23 / 23 | `redrawSignal` 每帧触发一次重绘 |
| app surface actual frames | 23,0 jank | 只重绘不重测,当然不 jank |
| UI Running 43.4 → 14.5ms | ↓ | measure/layout 全没了 |
| RT Running 39.0 → 43.2ms | ↑ | 多了一层 `RenderNode("AndroidEdgeEffectOverscrollEffect")` + RenderEffect |
| 录屏位移 0 / 约 0.5k px/s | — | 不是列表在滚,是 stretch 在回弹(亚像素蠕动) |

**为什么 EdgeEffect 出现在列表「中段」**:那不是列表的末尾,是**已加载内容**的末尾。
`t57-rich-topic.pb` 里四次塌陷各自有一对配套事件:

| 请求发出(A 帧) | 数据落地(B 帧) | 往返 | stretch 起点 |
|---:|---:|---:|---:|
| 3,786.2ms | 3,919.1ms | 132.9ms | 3,923.7ms(52 帧 / 422.0ms) |
| 4,801.5ms | 5,025.8ms | 224.4ms | 4,918.2ms(53 帧 / 433.4ms) |
| 5,691.2ms | 5,815.7ms | 124.6ms | 5,808.7ms(53 帧 / 432.3ms) |
| 6,589.5ms | 6,714.2ms | 124.7ms | 6,723.0ms(43 帧 / 349.8ms) |

A 帧的特征是 `recompose×11–13 + applyChanges + Compose:sideeffects`(`shouldLoadMore`
点亮 → `LaunchedEffect` 重启 → 发请求);B 帧多出 `onRemembered×3 / onForgotten×2 /
TextLayout:initLayout×8`(新一页的行真的被组合出来)。同一帧里 `animation` 在
`Recomposer:recompose` **之前**跑 —— fling 那一下 `scrollBy` 看到的还是旧的、已经见底的
列表,所以「新页到了」和「fling 死了」同帧发生。

三条独立旁证:

- **prefetch 断流**。`compose:lazy:prefetch:compose` 在四次塌陷前分别空了
  69.8 / 172.0 / 61.3 / 57.2ms —— 那几十毫秒里列表明明还在滚(每帧都有
  `animation + measureAndLayout`),却**没有下一项可以预取**,因为后面真的没有项了;
- **`execute:urgent`**。4,295.7ms 与 6,666.1ms 各有一次
  `compose:lazy:prefetch:execute:urgent`,即滚得比预取还快;
- **节律**。四次塌陷落在第 4、6、8、10 手 —— 每两手一页。按录屏
  13k px/s、行高约 330px、一手约吃 13 行算,一页约 26–30 行,正好两手一页。

**一轮那条「不是内容耗尽」的结论要撤回一半**。它是对的那一半:塌陷当帧确实**不是整个
列表的末尾**(还有下一页,`LoadingFooter` 也未必在场 —— 请求刚发出的头一帧
`loadingNextPage` 还没翻,而且截图取的是另一份样本 `fixed-3`)。它错的那一半是把
「不是列表末尾」当成了「内容没耗尽」:耗尽的是**这一刻已加载的那些行**,而
`LazyListState.canScrollForward` 只认已加载的行 —— 对 fling 来说,已加载内容的末尾和
真正的末尾是同一堵墙。

旧判据 `last >= totalItemsCount - 6`:6 行约 2,000px,在 13k px/s 下只有约 155ms 余量,
而实测往返 124–224ms —— **是个抛硬币**,这一轮 5 次抛输了 4 次(第 5 次是 3,158ms 那
5 帧的小 stretch)。一轮的 `shouldTurnPageAtEnd` 与 `ListPullToRefresh` 都动不到这条链,
所以复验照旧复现。

### 根因(楼层流):瞬时换页本身不产帧

一轮把翻页挪到静止态之后,`t57-rich-floor.pb` 的 page 3→4 是:

- 3,599.957ms ACTION_UP;fling 只跑到 3,623.6ms(列表已在本页页尾,`isScrollInProgress` 落下);
- 3,632.023ms 一个 **18.921ms** 的 doFrame(`recompose×13`、`initLayout×78` = 整页组合);
- 3,651.504ms 最后一个 app frame,之后 UI 睡 333.420ms、RT 睡 334ms;
- 3,991.677ms 下一次 ACTION_DOWN,4,005.483ms 才有下一帧。

app surface 空 353.979ms,录屏侧记 265.900ms「无新内容帧」。原因不是重活也不是背压:
`scrollToPage` 是瞬时换页,**它自己不产帧**,而新的一页是静止画面 —— 翻页在时间轴上
是一个点,不是一段。

### 改动

- 新增 `ui/common/PagedList.kt`:
  - `shouldLoadNextPage()` / `rememberShouldLoadNextPage()`——拉页判据从「还剩 6 项」
    换成**按距离**的「还剩不到 `PREFETCH_SCREENS = 2.5` 屏」。真机口径下是约 5,825px /
    约 390ms 余量,对同一批 124–224ms 往返有 1.7–3 倍安全系数(旧口径只有约 155ms)。
    量不出行高/视口时退回 `MIN_ITEMS_AHEAD = 6` 的老口径。
  - `flingHandoff()` / `PagedFlingBehavior` / `rememberPagedFlingBehavior()`——
    **余速的消费约定**:跑完了交还 0;前向还能滚(顶边到头等)原样交还;
    真到底(没有下一页)原样交还 —— **真边缘的 overscroll 一点不改**;
    只有「前向暂时滚不动 + 下一页还在路上」才扣住余速,`snapshotFlow` 等
    `canScrollForward` 转真(封顶 `FLING_CONTENT_WAIT_MS = 250ms`、
    `MAX_FLING_RESUMES = 3`),然后把**原速度**接着跑完。等待期间手指按下会以
    `MutatePriority.UserInput` 取消整条协程,交接是标准路径。超时也返回 0 ——
    宁可少一次视觉反馈,也不要那 430ms「有帧、120Hz、内容不动」。
- 五处分页列表(版块主题列表、精华区、收藏主题、他的主题/回复、搜索结果)统一换成
  `rememberShouldLoadNextPage` + `flingBehavior = rememberPagedFlingBehavior(...)`。
- `ui/topic/TopicScreen.kt`:外部换页从无条件 `scrollToPage` 改成
  **相邻页走 `animateScrollToPage`**(`tween(Motion.DURATION_PANEL = 220ms,
  Motion.easeDecelerate)`,与设计稿横滑回弹同一档),跨页跳转仍瞬时 ——
  新判据 `shouldAnimatePageTurn(from, to) = abs(to - from) == 1`。
  从第 3 页跳到第 30 页时动画会把中间 27 棵子树一路扫过去,那才是真的卡。
  动画跑在 `MutatePriority.Default` 上,手指一按就被 `UserInput` 抢走,不与横滑打架。
  新页数据未就绪时 `TopicPageView` 本来就画 `PageSkeleton`(不会黑/白帧),
  `beyondViewportPageCount = 1` 预渲染相邻页,`TopicViewModel.onPageLoaded` 也已经
  `ensureLoaded(target + 1)` 预取下一页 —— 这三条二轮不用改。
- 一轮的 `ListPullToRefresh` 与 `shouldTurnPageAtEnd` **都保留**:前者消掉了每次松手
  白等一帧 + 每个滚动帧一次 `MutatorMutex.mutate`,后者保证翻页不打断 fling ——
  两条都是真收益,只是都不是本票的根因。

### 单测

- `ui/common/PagedListTest.kt`(12 例):拉页判据的四档(两屏半内/外、退回项数口径、
  空列表),「新口径必须比旧的六项口径早、且余量覆盖 224ms 往返的 1.5 倍」的量化断言,
  以及 `flingHandoff` 的完整消费约定(跑完 / 真到底 YIELD / 顶边 YIELD / 临时见底 HOLD /
  接力上限),外加两例把 `PagedFlingBehavior` 的接线跑通。
- `ui/topic/EndReachedTest.kt` 追加 `PageTurnAnimationTest`(3 例):相邻页动画、
  跨页不动画、同页不动。

`./gradlew :app:assembleDebug :app:testDebugUnitTest` 绿。

### 复验怎么判

按票面验收期望重跑固定节奏。修复的可证伪点很具体:

- 主题列表塌陷窗内**不该再有** `drawLayer [AndroidEdgeEffectOverscrollEffect]` 的 RT 帧
  (除非真滚到了整个列表的末尾);
- `compose:lazy:prefetch:compose` 不该再出现几十毫秒的断流;
- 楼层流 page N→N+1 应有约 220ms 的连续横向运动帧,而不是一个 18.9ms 的点。

若主题列表仍有同量级塌陷且 trace 里**没有** EdgeEffect 帧,那就是另一条路,
按一轮留的口径把 RN 同轮对照一起交上来。

## 三轮修复(2026-08-27)

二轮的两处改动**方向对、纵深不够**,而其中一处(`PagedFlingBehavior` 的余速扣留)
本身就是复验里那些静止窗的直接来源。三轮先把复验交上来的两个可疑点逐一坐实,
再按结论改。归因全部来自二轮复验自己留下的
`acceptance/perf/t57-{topic,floor}-r2-phase.csv`(逐帧位移),没有新采样。

### 可疑点 1(主题列表):静止窗就是 HOLD,预取没接错线,是纵深不够

**1a. 108–283ms 静止窗 = `FLING_CONTENT_WAIT_MS` 的显式冻结 —— 坐实。**

把 `t57-topic-r2-phase.csv` 的四个分页边界逐帧摊开:

| 撞墙时刻 | 撞墙前 4 帧速度(px/s) | 静止时长 | 恢复速度 | 谁把它拉起来 |
|---:|---|---:|---:|---|
| 1.594s | 15,000 → 3,544 → 1,959 → 850 | **333ms** | 14,470 | 下一次 ACTION_DOWN |
| 2.609s | 14,324 → 5,049 → 1,388 → 495 | **233ms** | 16,650 | 下一次 ACTION_DOWN |
| 3.582s | 15,407 → 1,467 → 984 → 480 | **291ms** | 21,235 | 下一次 ACTION_DOWN |
| 4.597s | 17,637 → 4,629 → 1,295 → 497 | **291ms** | 19,817 | 下一次 ACTION_DOWN |

- 「4 帧从 15k 掉到 0」不是 fling 的指数衰减,是**一堵墙**;
- 墙后面那 233–333ms 里 `dy` 恰好为 0(只剩 ±4px 的 stretch 回弹噪声),
  与 `PagedFlingBehavior` 在 `withTimeoutOrNull(250ms)` 里挂起、整条 fling 协程
  占着 `ScrollScope` 一动不动完全同形;
- **`FLING_CONTENT_WAIT_MS = 250ms` 自己就大于验收的 100ms 闸** ——
  只要 HOLD 触发一次,这张票在结构上就不可能过。二轮把 EdgeEffect 的亚像素蠕动
  换成了「明写的静止」,观感一模一样。

**1b. 等待结束后并没有以原速续跑 —— 坐实,而且接力压根没发生过。**

4/4 个边界都是**静止到下一次 ACTION_DOWN 才动**(恢复速度 14k–21k 全是新手势的
拖拽速度,不是 fling 续跑)。也就是说 `withTimeoutOrNull` 全部超时、
`performFling` 走的是 `return 0f` 那一支。票面那处「速度只剩前段 6.5%」
(942 / 14,400)对应的是**冲进冻结的那 4 帧斜坡**的 100ms 分桶中位数,
不是「接力后速度低」。

**1c. 距离预取为什么仍每两手耗尽 —— 接线没问题,是纵深不够。**

逐条排掉票面列的三个嫌疑:

- `LaunchedEffect(listState, hasNextPage, loadingNextPage)` 的重启条件:
  重启只发生在 `loadingNextPage` 翻转时,重启后 `snapshotFlow` 会把当前值重新发一次,
  是**多打一次**而不是吞掉;`TopicListRepository.loadNextPage` 当场按
  `loadingNextPage` 去重,重复那一发不落地。**不是它。**
- 加载中去重吞掉提前触发:`shouldLoadMore` 在整段加载期间恒为真,
  `snapshotFlow` 的 distinct 只会少发不会晚发;真正决定时机的是它**第一次**翻真的时刻,
  而那由几何量决定。**不是它。**
- 串行瓶颈:**在**。`loadNextPage` 有 `loadingNextPage` 去重 + 每 key 一把 `Mutex`,
  同一时刻最多一发 `thread.php` 在飞。但它单独还不足以解释,得配上下面这个量。

真正的量在同一份 CSV 里:

| 每一把 `input swipe … 100` 跑了多远 | px |
|---|---:|
| 第 1 手 | 8,868 |
| 第 2 手 | 9,212 |
| 第 3 手 | 9,760 |
| 第 4 手 | 9,616 |
| 末尾那把没被打断的自然衰减 | **17,692** |
| 全程 | 55,132 |

全程 55,132px 吃掉约 5 页 → **一页约 11,000px(35 行 × 约 315px)**,
即**一把快甩正好吃掉一整页**。而 `PREFETCH_SCREENS = 2.5` 屏 ≈ 6,000px ≈
15k px/s 下的 400ms —— 这 400ms 要独自盖住请求往返 + 解析 + 合页 + 组合 35 行,
叠上「同时只能有一发在飞」,稳态就是「一手一页、每手都在页尾撞墙」,输了 4/4。

**结论:阈值不是调小一点的问题,是量级不对 —— 而且只要 fling 的终点还取决于
网络什么时候回来,再深的跑道也只是把抛硬币的赔率改一改。**

### 可疑点 2(楼层流):换页链子跑得很快,缺的是那 220ms 动画本身

`t57-floor-r2-phase.csv` 在唯一那处 184.0ms 空洞前后是:

```
2.515s dy=128 (14,418 px/s)   ← 还在满速
2.522s dy= 16 ( 2,045 px/s)   ← 撞到本页页尾,一帧掉光
2.548s dy=  0                 ← 静止
2.555s dy=184                 ← 新的一页整页出现
2.739s ← 这一帧与上一帧相隔 184.0ms,中间录屏一帧都没有
```

- **从撞墙到换完只用了 33ms(约 4 帧)**。那正好是
  `fling 结束 → isScrollInProgress 落下 → EndReachedReporter 的 derivedStateOf 重算
  → LaunchedEffect(reached) → onReachedEnd → goToPage → 重组 → LaunchedEffect(vm.page)`
  这条链的长度。**链子没被抢占、没被取消、跑得很快。**
- 换页发生在**一帧之内**(2.548 静止 → 2.555 新页整页在场),之后**一帧都不画**。
  这排除了「数据未就绪先显示骨架」那一条:骨架也好真内容也好,
  只要 pager 在动就该每帧都有横向位移;这里是**一个点,不是一段** ——
  与一轮 `scrollToPage` 的形状一模一样。
- 所以:**外部换页确实走到了 `LaunchedEffect(vm.page)`,但那 220ms 的
  `animateScrollToPage` 一帧都没画出来。** 只可能是两条路之一:
  1. 判据取到的 `from`(`pagerState.currentPage`)不是 `target - 1`,于是落进了
     `scrollToPage` 分支 —— `currentPage` 是 pager 的内部量,横滑收尾、
     `pageCount` 变化都会动它,拿它当「相不相邻」的依据本来就不该;
  2. `animateScrollToPage` 被**当场跑完**:Compose 的每一个 `tween`/`spring`
     都读协程上下文里的 `MotionDurationScale`,平台的
     `animator_duration_scale`(开发者选项 / 省电 / 无障碍「移除动画」)一旦是 0,
     `scaleFactor` 就是 0,动画一帧结束 —— 观感完全等价于 `scrollToPage`。
  在 JVM 侧分不开这两条(要么读设备设置,要么抓 trace),所以三轮**把两条一起堵上**。
  给复验一条一命令的判据:`adb shell settings get global animator_duration_scale`
  若为 0,那就是第 2 条;若为 1,那就是第 1 条。

`MutatePriority` 被抢占那条排掉了:动画跑在 pager 自己的 mutex 上,
同期只有 `listState.scrollToItem`(另一把 mutex)与 EdgeEffect 回弹(不占 mutex),
而且真被抢占的话链子不会在 33ms 内就把新页整页换上。

### 改动

**主题列表(`ui/common/PagedList.kt` + 五处分页列表):把 fling 和网络解耦**

- 新增 `tailPlaceholders()` / `rememberTailPlaceholders()` / `tailPlaceholderCount()`:
  **下一页在路上时,在列表尾部铺 `PLACEHOLDER_SCREENS = 2` 屏能滚的骨架行**。
  `canScrollForward` 于是一直为真,`DefaultFlingBehavior` 那条
  「不能消费 → `cancelAnimation()` → 余速交出去」的链根本不会启动 ——
  墙没了,EdgeEffect 不出,HOLD 也不触发。张数按「量出来的行高/视口」现算并
  在这一次加载期间**恒定**(`Snapshot.withoutReadObservation` 读 `layoutInfo`,
  不让组合订阅它;张数抖动等于每帧增删列表项)。骨架行高 = 实测平均行高,
  新页落地时骨架整批消失、真行在**同一批索引**上长出来,
  `LazyListState` 按「首个可见项 index + offset」锚定,视口里那一格不动。
- `PREFETCH_SCREENS` 2.5 → **4**:一把快甩要跑 3.7–7.4 屏,2.5 屏的跑道比一把
  fling 还短。4 屏 ≈ 9,600px ≈ 640ms,叠上骨架的 2 屏 ≈ 320ms,总预算约 960ms,
  对实测 124–224ms 的往返有 4 倍以上余量。**请求总数不变**(还是一页一发),只是每发都提前。
- `shouldLoadNextPage` 新增 `firstVisibleIndex` 闸:跑道加深之后
  「一页 35 行 ≈ 4.2 屏」只比阈值多一点点,进屏那一帧很容易顺手多打一发
  `thread.php`。**列表一动没动过就不预取**(ADR-0002),顶端那一行一滚出视口就恢复。
- `FLING_CONTENT_WAIT_MS` 250 → **80**:HOLD 退成纯兜底。它是本票病灶之一,
  所以它自己必须短于 100ms 闸 —— 哪怕真触发了也构不成一次超标的静止窗。
- 二轮已验证的真收益一个不动:`ListPullToRefresh`、`shouldTurnPageAtEnd`、
  `flingHandoff` 的「真到底原样交还余速(真边缘 overscroll 不变)」。

**楼层流(`ui/topic/TopicScreen.kt`)**

- 新增纯函数 `pageTurnFor(fromPage, toPage, pagerPage) -> PageTurn{NONE,ANIMATE,JUMP}`,
  把「这一次换页怎么落到 pager 上」从接线里抠出来:
  - `from` 改用**上一次呈现给用户的 vm 页码**(自动翻页 / 页码条恒定 ±1),
    不再依赖 `pagerState.currentPage` 这个我们不控制的内部量 —— 堵可疑点 2 的第 1 条;
  - `pagerPage == toPage`(横滑自己走完那一类)才返回 `NONE`,语义与二轮的
    `from == target` 早退一致。
- 相邻页的 `animateScrollToPage` 套一层 `withContext(FullMotion)`
  (`MotionDurationScale { scaleFactor = 1f }`),**不吃系统动画时长缩放** ——
  堵可疑点 2 的第 2 条。口径与平台一致:`RecyclerView.smoothScrollToPosition` /
  `ViewPager2.setCurrentItem(true)` 走 `Scroller` 而不是 `ValueAnimator`,
  本来就不吃这个缩放;它们是**内容连续性**,不是装饰。弹窗 / FAB / 抽屉那些
  装饰动画一律照旧尊重系统设置。
- `shouldAnimatePageTurn` 与「跨页跳转不动画」的语义原样保留。

### 单测

`./gradlew :app:assembleDebug :app:testDebugUnitTest` 绿。

- `ui/common/PagedListTest.kt`(20 例,新增 8 例):
  - `二轮的两屏半跑道比一把 fling 还短` —— 把 CSV 量出来的
    8,868 / 9,212 / 9,760 / 9,616 / 17,692px 写死进断言,5/5 都大于 2.5 屏;
  - `预取跑道加骨架跑道要盖住最慢的一次往返` —— 总跑道对 224ms @ 15k px/s
    必须有 3 倍余量;
  - `HOLD 兜底自己不许越过 100ms 闸` —— `FLING_CONTENT_WAIT_MS < 100`;
  - `骨架行按屏数铺` / `量不出行高时给兜底张数` / `骨架行张数有上下限` /
    `不在加载中就不铺骨架`;
  - `列表一动没动过就不预取`(含「真到眼皮底下时这条闸不拦」)。
- `ui/topic/EndReachedTest.kt`(新增 `pageTurnFor` 4 例):自动翻页/页码条走动画、
  横滑自己走完返回 `NONE`、跨页跳转 `JUMP`、
  以及 `相不相邻只看 vm 页码,不看 pager 内部量`(`pagerPage` 取 1 或 9 都仍 `ANIMATE`)。

### 复验怎么判

按票面固定节奏原样重跑两屏。可证伪点:

1. **主题列表分页边界不该再有「4 帧从满速掉到 0 + 一段 dy 恒为 0」的形状。**
   逐帧曲线上应看到 fling 平滑地滚过一段骨架行(灰条,行高与主题行一致),
   然后被真内容替换 —— 位移曲线连续,没有 >100ms 的 0 位移窗。
2. **富 trace 里既不该有 `drawLayer [AndroidEdgeEffectOverscrollEffect]`
   的中段 RT 帧(真滚到整个列表末尾除外),也不该有一段
   `Recomposer:animation` / measure 全为 0 而 UI traversal 照常出帧的窗口** ——
   后者是二轮 HOLD 的签名。
3. **`compose:lazy:prefetch:compose` 不该再出现几十毫秒的断流**:
   骨架行也是列表项,预取有得可取。
4. **楼层流 page N→N+1 应该有约 220ms 的连续横向运动帧**,
   录屏侧 `dt` 最大值在换页处应落回 8–25ms 量级,不再有 184ms 的空洞。
5. 顺手记一条环境量,能把可疑点 2 的两条路分开:
   `adb shell settings get global animator_duration_scale`
   (0 = 二轮那版动画必然一帧不画;1 = 二轮走错了 `scrollToPage` 分支)。
6. **回归口径**:真边缘(没有下一页了)的 overscroll 手感必须原样在;
   下拉刷新、到底自动翻页、跨页跳页三条都不许变;
   进版块后**不滚动**时不许多打一发 `thread.php`(`firstVisibleIndex` 那条闸)。

若主题列表仍有同量级静止窗、而 trace 里既没有 EdgeEffect 帧也没有 HOLD 签名,
那说明骨架跑道也被跑穿了(即请求往返在快甩下远超 960ms),
届时请把「请求发出 → 数据落地」的逐次往返时间一并交上来 ——
那就该动 `TopicListRepository` 的串行约束了。

**待真机复验。**

## 三轮修复终轮复验(2026-08-27)

设备 `25113PN0EC`，release/`speed-profile` 包 MD5
`98727e9b536714415f8939d806e6fd17`；测前记录
`animator_duration_scale=1.0`。全程亮屏、120Hz、前台为
`com.chasel.ng2.n/com.chasel.ng2n.MainActivity`，未卸载、未清数据。主题列表与楼层流
各原样跑一轮固定节奏脚本。

| 判据 | 终轮证据 | 裁决 |
|---|---|---|
| 1. 主题列表边界速度/停滞 | 780 帧/7.480s，基准 8.33ms；固定输入段最低 100ms 速度桶 8.862k px/s，约为相邻前段 15.299k 的 **57.9%**；无 `>100ms` 零位移/无新内容窗、无内容突现 | 通过 |
| 2. EdgeEffect / HOLD | 35.6MB 富 trace 中 `AndroidEdgeEffectOverscrollEffect` **0**；`animation` 771 个，起点间隔 p95/max 8.881/17.032ms；录屏也没有二轮 HOLD 的静止形态 | 通过 |
| 3. lazy prefetch | `compose:lazy:prefetch:{compose,measure,apply}` 各 247 个，urgent 13 个；最大原始间隔位于最后一手自然衰减尾段，分页处没有对应的位移冻结 | 通过；按“是否形成分页断流”判，不把按行按需调度间隔冒充断流 |
| 4. 楼层相邻页动画 | page 1→2 从 2.656s 连续运动到约 2.848s（约 **192ms**），动画帧间隔 7.3–9.7ms；旧版 184ms 一帧换页空洞消失 | 通过 |
| 5. 动画缩放环境量 | `animator_duration_scale=1.0` | 已记录 |
| 6. 回归 | 固定脚本实际覆盖到底自动翻页；主题/楼层 missed-vsync 均 0。真边缘、下拉刷新、跨页跳转沿用本轮已有单测与前轮功能证据，本终轮不另扩脚本 | 无回归证据 |

场景 3 回归为 0/1,558 janky（0.00%，p95 9ms），场景 4 回归为 1/1,238
（0.08%，p95 11ms），均不差于 0.02% / 0.43% 基线。主题列表的尾部骨架在本轮网络
条件下很快被真行替换，单帧截图未长期保留灰条；但列表持续滚动且没有边界冻结，核心
可证伪形态已经消失。综上四个硬闸全部通过，票 57 改为 **verified**。

证据：`acceptance/perf/t57-r3-animator-duration-scale.txt`、
`t57-topic-r3-{framestats,framestats-analysis,phase-summary,phase,rec,focus}.txt/csv`、
`t57-floor-r3-{framestats,framestats-analysis,phase-summary,phase,rec,turn-diff,focus}.txt/csv`、
`t57-topic-r3-rich-analysis.txt`。富 trace（不进 git）：`/tmp/t57-topic-r3-rich.pb`，
SHA-256 `4f344b0a7d7dfa8a3d4f117218b85261ecb871a30e92e49ba51bf84b3bb17d79`。
