# 58 — P1:场景 9 FrameTimeline 呈一档 vsync 双峰

**Status:** resolved（裁定：系统侧队列深度粘滞，建议降 P2 并改判场景 9 验收口径；GPU 成本另拆子票）

**Severity:** P1（票 19 场景 9 硬闸失败；队列深度在两档间振荡）

## 现象

票 56 打通 Perfetto 替代流程后，在小米 `25113PN0EC`、原生 release
`com.chasel.ng2.n` 上采 15 秒设置返回、抽屉开合与首页 tab 转场。566 个有效
app surface→display 配对帧的 SurfaceFlinger actual frame start→present 明确形成两簇：

| 峰 | 帧数 | 占比 | 中位数 |
|---|---:|---:|---:|
| 低峰 | 383 | 67.7% | 10.162ms |
| 高峰 | 183 | 32.3% | 18.452ms |

两峰相差 8.290ms，恰为一档 120Hz vsync。两簇中的 present2present 都约 8.32ms，
运动中 SurfaceFlinger 同时报告 `(uid, frameRate)={10375,120.00Hz}`，已排除 HyperOS
变暗锁 60Hz(T1)。这正是 C8 定义的“送显仍满帧，但队列深度在 1↔2 间振荡”的双峰。

## 复现流程

```bash
adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/s9.pb \
  < scripts/perf/frametimeline.cfg
# 15 秒内执行设置返回、抽屉开合、首页 tab 转场
adb pull /data/misc/perfetto-traces/s9.pb .
scripts/perf/.venv/bin/python scripts/perf/analyze_frametimeline.py s9.pb \
  --package com.chasel.ng2.n --source device --vsync-ms 8.333
```

测前后按 T4 验前台，运动中按 T1 验 120Hz；禁止调用已卡死的 timestats enable/clear。

## 证据

- `.scratch/native-rewrite/acceptance/perf/s9-native-frametimeline-analysis.txt`
- `.scratch/native-rewrite/acceptance/perf/s9-native-frametimeline-sample.txt`
- 原始 trace（不进 git）：
  `/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s9-frametimeline.pb`
  （256,219 bytes；SHA-256
  `5b7e7eeece306cfa646961cf6746f6f1bd4872d505bbfea8e1aac0baef39b509`）

## 初步归因

高峰 183 帧全部对应 display `Late Present`，低峰 383 帧全部对应 `On-time Present`；
present cadence 在两边仍为 120Hz。优先排查 SurfaceFlinger/app buffer 队列在转场期间
由一深度切到另一深度，而不是 UI 线程持续超预算或刷新率降档。现有 trace 只采
FrameTimeline，足以判峰但不含 sched/atrace，不能继续臆测是哪一段 app/SF 工作触发。

## 验收期望

同脚本、同 release/profile、120Hz 有效样本中应收敛为 9–11ms 低延迟单峰；17–20ms
高峰不得达到 5%，且修复不回退场景 9 已通过的“无连续丢 >2 vsync”子项。

## 富 trace(2026-08-24)

为定位峰的归属，按同样 15 秒场景重采一份富 trace。配置在
`acceptance/perf/s9-native-rich.cfg`：64MiB ring buffer，同时启用
`linux.ftrace`（gfx/view/input/sched/freq/binder_driver，`atrace_apps` 仅
`com.chasel.ng2.n`）与 `android.surfaceflinger.frametimeline`。操作覆盖设置返回、
三组抽屉开合/抽屉内滚动、四次 tab 切换；首尾焦点均为原生包，全程未调用 timestats。

原始 trace：

```text
/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s9-rich.pb
43,636,505 bytes
SHA-256 f7da877d69295690039028769b4becbec05de79d42b7fa86e210dea1ce0ac3eb
```

### 总体峰形

915 个 surface→display token 中有 910 个有效 presented 帧（另 5 个孤立 Dropped
Surface Frame 不参与峰形）：低峰 409(44.9%)、中位 9.964ms；高峰 501(55.1%)、
中位 18.337ms；present2present 中位 8.319ms。富 trace 再次确认双峰。

### app / SF 归属

把每个 token 的 app actual surface 区间与 main/RenderThread sched Running、atrace slice
对齐，再把 display actual 区间与 SurfaceFlinger 主线程/HWC slice 对齐：

| 指标(ms) | 低峰 med / p95 | 高峰 med / p95 |
|---|---:|---:|
| app frame wall | 6.857 / 8.083 | 9.918 / 10.861 |
| UI thread CPU | 0.143 / 1.764 | 1.358 / 2.727 |
| UI traversal wall | 0.000 / 1.527 | 0.595 / 1.165 |
| RenderThread CPU | 3.041 / 3.653 | 3.121 / 4.565 |
| RT Drawing wall | 2.094 / 2.506 | 1.853 / 2.565 |
| dequeueBuffer | 0.049 / 0.061 | **0.083 / 0.138** |
| queueBuffer | 0.358 / 0.493 | 0.348 / 0.481 |
| buffer ready→SF start | 7.172 / 9.549 | **3.790 / 4.897** |
| SF main CPU | 2.794 / 3.172 | 4.242 / 4.768 |
| SF HWC wall | 1.135 / 2.355 | 2.492 / 3.485 |
| SF start→present | 9.964 / 10.173 | **18.337 / 18.652** |

高峰里的 UI CPU max 6.929ms、RenderThread CPU max 5.874ms、dequeue max 0.221ms、
SF main CPU max 5.395ms，四者均为 0 帧超过 8.333ms。故不是 UI thread 长帧、
RenderThread 长帧或 dequeueBuffer 阻塞；SF/HWC CPU 本身也未跑爆预算。高峰 buffer
在 SF actual frame start 前中位 **3.790ms** 已 ready，额外完整一档 vsync 最终记在
SF start→present。现有证据最支持**app→SF 管线相位 / present slot 的队列深度选择**，
而非某段 CPU 代码执行过慢；修复定位应盯帧相位/队列交接，不应先优化 Compose 布局或
dequeue。

### 5 个典型 Late Present 逐段时间线

表内 UI/RT/SF 为 on-CPU；括号内分别是 UI traversal、RT Drawing、HWC wall：

| 交互 | t/token | app wall | UI CPU(trav) | RT CPU(draw) | dequeue | ready→SF | SF CPU(HWC) | SF→present |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 抽屉打开 | 1.102s / 153374075 | 9.888 | 1.660(0.646) | 2.017(0.915) | 0.040 | 3.690 | 4.677(2.275) | 18.192 |
| 抽屉关闭 | 1.701s / 153374882 | 9.663 | 1.859(0.723) | 2.832(1.572) | 0.112 | 3.777 | 4.566(2.469) | 18.148 |
| 抽屉内滚动 | 3.798s / 153376128 | 9.333 | 1.777(0.502) | 2.296(1.287) | 0.060 | 4.006 | 4.464(2.145) | 18.387 |
| 设置返回 | 5.500s / 153377127 | 9.048 | 1.309(0.555) | 2.159(1.577) | 0.082 | 4.367 | 4.346(2.263) | 18.092 |
| 第二轮抽屉 | 11.048s / 153382298 | 10.189 | 2.043(0.663) | 4.487(2.529) | 0.096 | 2.958 | 4.510(3.396) | 18.641 |

### 与交互的相关性

由 trace 内 14 个 `input` 进程的 `start_ts` 标定操作时刻，不靠人工猜视频时间：

| 交互窗口 | 高峰 | 低峰 | 高峰率 |
|---|---:|---:|---:|
| 第一轮抽屉开合 | 102 | 0 | **100%** |
| 抽屉打开→抽屉滚动→进入设置 | 166 | 0 | **100%** |
| 设置返回尾段 | 26 | 0 | **100%** |
| 四次 tab 切换 | 1 | 409 | **0.2%** |
| 第二轮抽屉开合 | 103 | 0 | **100%** |
| 第三轮抽屉开合 | 103 | 0 | **100%** |

501 个高峰帧中 **500 个(99.8%)** 落在抽屉/设置链，tab 窗口只有边界 1 帧；tab 的
409 个持续帧全部低峰。结论：Late Present 与抽屉/设置链强相关，不是全局每种转场
都会出现。完整数字见 `acceptance/perf/s9-native-rich-analysis.txt`。

## 裁定(2026-08-24)

用 `perfetto.trace_processor` 直接查同一份 43MB 富 trace（`s9-rich.pb`，SHA-256
`f7da877d…0ac3eb`）。结论：**双峰不是「每帧振荡」，而是一个二值、粘滞的管线深度状态**
——app→SF→DPU 在整段时间里只翻了 3 次面，翻进去就不出来。这跟票面「抽屉链特有」的
读法**不一致**，下面逐条给数字。

### 一、先枪毙几个方向（trace 直接否掉）

| 假设 | trace 事实 | 结论 |
|---|---|---|
| 抽屉/设置引入第二个 window / dialog / SurfaceView | SF 全程只合成 3 个 layer：`MainActivity#97282`、`StatusBar#118`、`NavigationBar0#117`。929 帧里 `setBuffer MainActivity` 910 次、`setBuffer StatusBar` 20 次 | 否 |
| blur / RenderEffect / 阴影逼出 GPU（client）合成 | 全部 929 个 display frame `hasClientComposition=0`、`gpu_composition=0`；SF 的 `RenderEngine` 线程只跑了 44 次，全是 `drawLayersInternal for RegionSampling`（状态栏亮度采样） | 否 |
| 刷新率降档 / `setFrameRate` 变化 | 936 次 commit 全部 `Vote ExplicitExact` → `ExplicitExact 120.00 Hz (1.00)`，`renderRate 120.00 Hz , mIdealPeriod 8333333` 2684 次，无一例外 | 否 |
| SF/HWC CPU 跑爆 | 见票面富 trace 段，SF main CPU max 5.395ms | 否 |

### 二、高峰帧到底是什么：DPU 队列深了一档

按 display frame 分组，对齐 SF 主线程、HWC(`vendor.qti.hard`)、内核 `crtc_commit:213`：

| 指标（中位数，ms） | 低峰(409) | 高峰(501) |
|---|---:|---:|
| SF 帧起点 → 自己的 present | 9.964 | **18.337** |
| present 比 SF 自己的预测晚 | 0.000 | **+8.319**（正好一档） |
| SF 帧起点 → **上一帧**的 present | −1.643 | **−10.010** |
| `atomic_commit` 提交点 → 上一帧 present | +0.683 | **−6.710** |
| `atomic_commit` 阻塞时长 | **0.061** | **7.341** |
| 内核 `complete_commit` 时长 | 7.993 | 8.132 |
| `atomic_commit` 结束 → present | 7.527 | 7.745 |

高峰时 SF 在**上一帧还没送显**的时候就开始合成下一帧，DRM 原子提交因此在内核里
干等 7.34ms（低峰 0.06ms）。内核侧 `complete_commit` 两边一样（8.0 vs 8.1ms），
即 **DPU 没有变慢，只是被排到了下一个 slot**。SF 自己的计数器同口径复核：

- `PrevFramePending` / `PrevFrameMissed` / `PrevHwcFrameMissed`：高峰帧 96.6%–97.6% 为 1，
  低峰帧 0.2%
- `BufferTX - …MainActivity#97282`：高峰中位 **2**，低峰 **1**
- `TransactionQueue`：高峰中位 **2**，低峰 **1**

即典型 buffer stuffing——**多压了一个 transaction 在 SF 手里**，节奏（present2present
8.319ms）一点没变，只是每帧多背一档 8.33ms 延迟。票 53「录屏无停格」与此完全自洽。

### 三、它是粘滞的：15 秒里只翻面 3 次

按 display frame 时序找模式切换点（H=SF 帧 >14ms，L=<14ms）：

```
t= 1015.46ms  → H   （第一次抽屉开，冷态首帧；jank=Prediction Error）
t= 6831.08ms  → L   （tab1 切换，app 掉了一帧：doFrame 16.33ms / Dropped Frame）
t=10882.54ms  → H   （第二次抽屉开首帧；app 帧 13.73ms / App Deadline Missed）
```

全程仅此 3 次。**空闲不解**：H 态跨过 5.58→6.82s 的 1.24s 静置照样是 H；L 态跨过
10.42→10.88s 的 0.46s 静置照样是 L。整段 trace 里**唯一一次自愈是靠丢帧**
（t=6831.08，`AndroidOwner:measureAndLayout` 14.46ms 撑爆 doFrame → Dropped Frame →
队列排空 → 回 L）。

进 H 的那一刻在 SF 侧留了明证，t=10888.207 的 SF vsync：

```
transactionReadyTimelineCheck
  frameIsEarly vsyncId: 153381956
  expectedPresentTime-sf: 289397460608340
  predictedPresentTime-app: 289397467993303   ← 比 sf 晚 7.385ms
  earlyLatchVsyncThreshold: 4166666
…
onCommitNotComposited        ← 这一拍 SF 只 commit 不合成，把 app 的 transaction 押后一档
```

押后之后 app 仍按 1 帧/vsync 生产，队列就再也排不空了。

**这解释了票面「500/501 在抽屉链、tab 只有 1/410」的相关性是怎么来的**：不是抽屉路径
独有的病。tab1 切换（t=6822.55）**同样**触发了 App Deadline Missed 进 H 的条件，只是它
紧接着掉了一帧、把队列冲干净了，后面三次 tab 才全在 L 态。**换句话说 tab 链是「运气好」，
不是「结构上更优」。**

### 四、为什么两侧的自愈机制都没触发

- **HWUI 侧**：`CanvasContext::isSwapChainStuffed()` 靠 `dequeueBuffer` 变慢来识别 stuffing。
  本例 `dequeueBuffer` p95 只有 0.138ms、max 0.221ms，从不阻塞（缓冲区够用），所以 HWUI
  永远不认为自己被塞住，不会主动丢帧排空。
- **SF 侧**：910 帧里 FrameTimeline 判成 `Buffer Stuffing` 的只有 **1** 帧，其余 495 帧都是
  `Unknown Jank` + `on_time_finish=1`，SF 的 stuffing 缓解逻辑同样不触发。

**两侧的自动排空都因为「谁都不超时」而失效** —— 这是这台 HyperOS/Adreno 设备上的系统侧
缺口，app 没有直接 API 去要求 SF 排空队列。

### 五、app 侧确实有一根杠杆：抽屉/设置路径的 GPU 光栅成本

按交互窗口统计 app 的 GPU fence 等待（`waiting for GPU completion`，即这一帧 GPU 真实耗时）
与 RenderThread 的显示列表录制（`Drawing`）：

| 窗口 | GPU 中位 | GPU p95 | GPU max | >8.333ms 帧数 | RT `Drawing` 中位 |
|---|---:|---:|---:|---:|---:|
| 抽屉-1 | 5.45 | 8.01 | 8.66 | 2 | 1.76 |
| 抽屉-2+滚动+进设置 | 6.38 | 8.20 | 10.98 | 4 | 1.79 |
| 设置 | 6.21 | 10.29 | 11.92 | 3 | 1.61 |
| **tab 切换** | **1.31** | **2.23** | 9.69 | 1 | 2.09 |
| 抽屉-2 | 5.30 | 7.22 | **9.33** | 2 | 2.12 |
| 抽屉-3 | 5.59 | 8.03 | 8.48 | 2 | 1.91 |

RT 的 CPU 侧录制两边一样（1.6–2.1ms），差的**全是 GPU 光栅**：抽屉链是 tab 链的 4 倍，
p95 已经贴着 8.333ms 预算。而**触发第三次翻面的那一帧就在这里**——t=10882.54 抽屉开首帧，
当时管线还在 L 态（对照组成立，不是 H 态的果），GPU 单帧 **9.33ms**，直接把预算撑爆：

```
10887.06  GPU completion  waiting for GPU completion 1328   dur=9.33
10899.85  …               waiting for GPU completion 1329   dur=1.11
10903.98  …               waiting for GPU completion 1330   dur=1.60
```

抽屉这一路每帧的 GPU 工作是：整屏首页重画 + **整屏 scrim alpha 混合**
（`DrawerHost.kt` 的 `drawBehind { drawRect(colors.scrim, alpha = progress) }`）+
300dp 满高面板的 `.shadow(Elevation.level2)`。屏宽 1220px、面板 300dp≈900px，
**开到底时 scrim 有约 74% 的面积被不透明面板整个盖住，属于纯浪费的整屏混合**。

### 六、裁定与建议

1. **不改代码。** 现有证据指向「一次 GPU 超预算 → SF 押后一档 → 管线永久深一档」，
   而 app 没有让 SF 排空队列的 API；两侧自动缓解都因为「谁都不超时」而不触发。
   任何盲改（砍 scrim 面积、去 `.shadow`、改 `visible` 门控）都是**渲染可见性改动**，
   本轮没有真机、且视觉对拍票正在并行跑，不具备验证条件，硬改不符合「拿数字说话」。
2. **P1 → P2 降级建议。** 现象是**延迟档位**（每帧多背一档 8.33ms），不是节奏缺陷：
   present cadence 中位 8.319ms、连续丢 >2 vsync 子项未回退、票 53 已证录屏逐帧连续。
3. **验收口径建议改判。** 场景 9 的硬闸建议改为量 **present cadence + 丢帧数**
   （8.32±0.5ms、无连续丢 >2 vsync），把 `SF start→present` 的双峰降为**观测项**记录
   而非闸门——因为它在这台设备上取决于「本次会话有没有恰好掉过一帧」，
   同一份代码复采两次可以给出完全相反的结论（本 trace 里 tab 链与抽屉链就是同因异果）。
4. **拆子票（真机在手时做）：削抽屉/设置路径的 GPU 光栅**，目标 **p95 < 6ms、
   单帧 max < 8.333ms**（今天是 p95 8.0–8.2 / max 9.33–11.92）。候选（按性价比排）：
   - scrim 只画面板右边缘到屏幕右边的那一条（被不透明面板盖住的 ~74% 不画），
     视觉等价，省掉每帧约 2.4Mpx 的整屏混合；
   - 面板的 `.graphicsLayer{translationX}` 与 `.shadow(level2)` 合成同一个
     `graphicsLayer`（少一个 RenderNode），并核对 `clip` 是否必要；
   - 抽屉首帧的文字首次栅格化（`Compose:onRemembered` 那一帧）——若要保 `visible`
     门控的命中测试语义，可只预热字形而不改门控。
   每一项都必须**改后重采同一份 15s 脚本对拍 GPU fence 分布**才算数。

**待真机复验**：本裁定未改代码，`:app:assembleDebug :app:testDebugUnitTest` 绿（无回归风险）。
若按第 4 条改 GPU 成本，复验期望仍按票面：120Hz 有效样本收敛为 9–11ms 低延迟单峰、
17–20ms 高峰 <5%，且「无连续丢 >2 vsync」不回退。

**复现本裁定的查询**：`scripts/perf/.venv/bin/python`（已装 `perfetto`），
`TraceProcessor(trace='…/s9-rich.pb')`，关键表 `actual_frame_timeline_slice` /
`expected_frame_timeline_slice`（`upid` 分别取 pid 8490=app、3003=surfaceflinger），
关键 counter `BufferTX - …MainActivity#97282`、`TransactionQueue`、`PrevFramePending`，
关键 slice `atomic_commit`(tid 3357)、`complete_commit`(tid 1505)、
`waiting for GPU completion%`、`frameIsEarly%`、`onCommitNotComposited`。

## 票 59 后合并重测(2026-08-27)

在 MD5 `2c985a568f20326ff44aae592edcd02d` 的 release/`speed-profile` 包上，按同一份
15 秒富配置重采「设置返回 + 6 轮抽屉开合」。有效 trace 36,175,875 bytes，SHA-256
`239f34edf75e1386048334bc8fcf15838c400b4d16262713211493f59b9aff74`；收尾焦点仍为
本 app，present2present 中位 8.319ms，满足前台/120Hz 前置条件。

总体 755 个 presented 配对帧：低峰 195（25.8%，中位 10.025ms），高峰 560
（**74.2%**，中位 18.306ms）；远高于 `<5%`。同一 app surface 时间窗内 757 个
`waiting for GPU completion` slice 的 GPU p50/p90/p95/max 为
5.043/7.318/**7.695**/11.557ms，14 帧超过 8.333ms，也未满足 `<6ms`。

逐段结果：

| 窗口 | 高峰率 | GPU p95 / max |
|---|---:|---:|
| 设置返回 | 1.2% | 8.326 / 9.080ms |
| 抽屉 1 | 100% | 7.227 / 9.136ms |
| 抽屉 2 | 100% | 8.084 / 9.012ms |
| 抽屉 3 | 100% | 7.290 / 10.445ms |
| 抽屉 4 | 0.9% | 2.814 / 11.557ms |
| 抽屉 5 | 100% | 7.097 / 10.731ms |
| 抽屉 6 | 100% | 7.454 / 8.816ms |

归因仍与原裁定一致：票 59 去掉纯 overdraw 后没有把抽屉的 GPU 尾部压进预算，5/6 个
抽屉窗口 p95 仍 >6ms，单帧 8.8–10.7ms 足以把管线推入高档；第 4 轮短暂回到低档，
随后第 5 轮又整体翻回高档，正是「偶发排空后自愈、下一次超预算再粘住」的二值状态，
不是 UI/RT 持续 CPU 长帧。按要求**不改判级**：场景 9 仍不通过，本票原 P1 记录与
降级建议均保持现状。

证据：`acceptance/perf/s9-final-{frametimeline,gpu,segment}-analysis.txt`、
`s9-final-focus.txt`；原始 trace（不进 git）：`/tmp/s9-final.pb`。
