# 58 — P1:场景 9 FrameTimeline 呈一档 vsync 双峰

**Status:** open

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
