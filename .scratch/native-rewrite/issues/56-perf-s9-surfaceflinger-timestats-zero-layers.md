# 56 — 场景 9 SurfaceFlinger timestats 返回 0 层

**类型:** performance infrastructure
**优先级:** P2
**状态:** open

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
