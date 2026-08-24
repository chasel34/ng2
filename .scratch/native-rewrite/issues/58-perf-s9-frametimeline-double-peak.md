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
