# 57 — P1:连续快甩时滚动速度塌陷/停滞

**Status:** open

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
