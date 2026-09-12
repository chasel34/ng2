# 56 — 场景 9 SurfaceFlinger timestats 返回 0 层

**类型:** performance infrastructure
**优先级:** P2
**状态:** resolved

## 现象

票 19 场景 9 按 playbook 仅执行一轮 `SurfaceFlinger --timestats` enable/clear/dump，
dump 返回 0 字节、0 层，无法取得要求的 latch2present 单峰/双峰直方图。该设备历史上
已记录反复 enable/clear 会让 timestats 卡死（T2），因此验收纪律禁止继续重试污染状态。

同轮有效前台转场补采 framestats 与录屏：4,786 帧、现代 janky 1/4,786（0.02%）、
missed-vsync 1；录屏最大运动帧间隔 18.6 ms（基准 8.34 ms），没有连续丢 2 个以上
vsync。该降级证据能证明连续丢帧子项，但不能替代 latch2present 分布，所以场景 9
按“未证明”不过闸，而不是把 0 层伪装成单峰。

## 证据

- `acceptance/perf/s9-native-timestats.txt`（0 字节）
- `acceptance/perf/s9-native-framestats-valid.txt`
- `acceptance/perf/s9-native-rec-analysis.txt`
- 录屏（不进 git）：`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s9-native-transitions.mp4`

## 期望

提供可重复取得 latch2present 的单轮采样流程，或建立等价且可审计的替代指标；不得要求
验收者反复 clear 已卡死的 SurfaceFlinger 状态。

## 解决与真机复验(2026-08-24)

### 可重复流程：Perfetto FrameTimeline

设备查询确认存在 `android.surfaceflinger.frametimeline` 数据源。固化配置为
`scripts/perf/frametimeline.cfg`，整轮不调用 timestats enable/clear：

```bash
adb -s 192.168.0.101:41641 shell perfetto -c - --txt \
  -o /data/misc/perfetto-traces/s9.pb < scripts/perf/frametimeline.cfg
# 上述命令运行的 15 秒内执行设置返回、抽屉开合、首页 tab 转场
adb -s 192.168.0.101:41641 pull \
  /data/misc/perfetto-traces/s9.pb s9.pb

scripts/perf/.venv/bin/pip install perfetto
scripts/perf/.venv/bin/python scripts/perf/analyze_frametimeline.py s9.pb \
  --package com.chasel.ng2.n --source device --vsync-ms 8.333
```

采样前后都必须按 T4 确认 `mCurrentFocus` 是目标包；运动中按 T1 确认 UID 的
`frameRateOverride=120Hz`。本轮第一份预采尾段越过原生首页进入 anzong，已整轮作废；
第二份有效样本首尾均为 `com.chasel.ng2.n/MainActivity`，运动中 UID 10375 为 120Hz。

### 指标语义

Android 16 的 FrameTimeline 表不直接暴露 SurfaceFrame 内部的 `lastLatchTime`。脚本
因此不把 app actual surface slice 的 `dur` 当 latch2present——该 slice 只结束在
buffer ready/acquire fence。正确替代口径为：

1. 从 `actual_frame_timeline_slice` 选中包含目标包名的主 layer；
2. 通过 `display_frame_token` 配到 `surface_frame_token is null` 的 actual display frame；
3. 取 display slice `dur`，即 SurfaceFlinger actual frame start→present；
4. 该固定前缀不影响峰形：@120Hz 低峰 9–11ms，高峰 17–20ms，二者差一个 vsync；
5. 次峰达到 5% 即判双峰。高延迟单峰也不冒充低延迟单峰通过。

这一路线直接来自 FrameTimelineEvent 的 actual display begin/end，present2present 则取
`display.ts + display.dur` 的相邻差；能够审计峰形，又不依赖已卡死的 timestats 状态。

### 有效样本与裁决

| 项 | 结果 |
|---|---:|
| trace | 256,219 bytes，15s，SHA-256 `5b7e7eee...b509` |
| app surface→display 配对 | 568；剔除 2 个孤立 Dropped Surface Frame 后 566 |
| 低峰 | **383(67.7%)**，中位 10.162ms |
| 高峰 | **183(32.3%)**，中位 18.452ms |
| present2present | 中位 8.319ms；两个峰段均约 8.32ms |
| 结论 | **双峰**，两簇相差一个 120Hz vsync |

两个 Dropped Surface Frame 相隔约 2.06s，不成簇，按 C7 属孤立平台余量；不影响 C8
双峰裁决。流程本身已打通并脚本化，故**票 56 基础设施问题 resolved**。但有效数据证明
原生场景 9 确有双峰，产品侧失败转票 58，不能把“有证据”误写成通过。

证据：

- `acceptance/perf/s9-native-frametimeline-analysis.txt`
- `acceptance/perf/s9-native-frametimeline-sample.txt`
- 原始 trace（不进 git）：
  `/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s9-frametimeline.pb`
