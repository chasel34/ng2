# 57 — P1:连续快甩时滚动速度塌陷/停滞

**Status:** reopened

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
