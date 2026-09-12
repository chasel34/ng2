# scripts/perf — 性能采样分析脚本

判据编号(C\*/X\*/T\*/P\*)一律以 [性能手册](../../docs/perf-playbook.md) 为准。
当前正式包名为 `com.chasel.ng2`，开发包名为 `com.chasel.ng2.dev`。
**模拟器与 debug 包的数据永远不能用于性能裁决**(T7/T8);下述三个通用分析脚本都要求 `--source`,
非 `device` 时在输出首段打「本次数据不可用于性能裁决」。

## 环境再生

`analyze_framestats.py` 只用标准库,系统 `python3` 直接跑。
`analyze_rec.py` 需要 pyav + numpy；`analyze_frametimeline.py` 需要 perfetto。homebrew 的
python 是 externally-managed,不能直接 `pip install`,用 venv:

```bash
python3 -m venv scripts/perf/.venv
scripts/perf/.venv/bin/pip install av numpy perfetto
```

`scripts/perf/.venv/` 已进 `.gitignore`(不入库,按上面两行重建)。

## analyze_framestats.py — C2 / C5 / C6

采样(测量前先按 T1/T4 确认:滚动中 `frameRateOverride {uid=<app> 120}`、前台焦点是被测 app):

```bash
adb shell dumpsys gfxinfo <pkg> reset
# 操作。input swipe 单次时长 >80ms,起步段 ≤80ms 不作证据(T5)
adb shell dumpsys gfxinfo <pkg> framestats > fs.txt
python3 scripts/perf/analyze_framestats.py fs.txt --source device
```

输出:样本规模、**app 每帧 CPU**(C5,`HandleInputStart→SwapBuffers`)、
整帧耗时、**丢帧**(C6,`IntendedVsync` 间隔 > `--drop-ms`,默认 13ms=120Hz 口径)、逐阶段均值、最差帧。

选项:`--source device|emulator|unknown`、`--drop-ms`(换刷新率时取 ≈1.5× 帧间隔)、
`--idle-ms`(≥ 该值的空档算静止,不计丢帧,默认 100ms)、`--skip-head-ms`(切掉注入手势起步段)、
`--top`、`--selftest`(内置回归,标准库,改脚本后跑一下)。文件参数省略或写 `-` 时读 stdin。

列一律按表头名定位——新版 framestats 在 `Flags` 后插了 `FrameTimelineVsyncId`,按下标取列会整体错位(T3)。

### Flags 怎么过滤(票 52)

hwui `FrameInfoFlags` 只有低 4 位是稳定语义:`WindowLayoutChanged=1`、`RTAnimation=2`、
`SurfaceCanvas=4`、`SkippedFrame=8`。**bit4 及以上是新版本追加的常态位,不代表帧无效**:
Android 16 / API 36 真机上 bit5(=32)几乎覆盖每一个交互/滚动帧(s3-native 120 帧里 119 帧是 32),
而同一份 dump 的现代 FrameTimeline 汇总是 6,318 帧 / 1 janky(0.02%)。
所以脚本按 `Flags & 13`(WindowLayoutChanged|SurfaceCanvas|SkippedFrame)剔除,未知高位一律放行;
再用「IntendedVsync ≤ HandleInputStart ≤ SwapBuffers ≤ FrameCompleted」兜底,挡掉跳过帧的残留时间戳
(那种行算出来是负几百毫秒)。旧的 `Flags != 0` 口径在 API 36 上会把整份采样清空。

丢帧只在运动段内算:`IntendedVsync` 间隔 ≥ `--idle-ms` 的空档是「app 没内容要画」,单独列出不计丢帧
——真机数据里这两类分得很开,卡顿空档 13~60ms,静止空档 ≥100ms(常有几百 ms 到几十秒)。

## analyze_rec.py — C1 / C10 / C11

```bash
adb shell 'screenrecord --time-limit 8 /sdcard/rec.mp4 & sleep 1; <操作>; sleep 2'
adb pull /sdcard/rec.mp4 .
scripts/perf/.venv/bin/python scripts/perf/analyze_rec.py rec.mp4 --source device
```

按 pts 求帧间 dt,下采样成灰度求相邻帧平均绝对差:

- **运动窗口** = 灰度差 > `--motion` 的连续段(允许 `--gap` 帧断口);
- **停格**(C10)= 运动窗口内 dt > `--stall` × 基准帧间隔;**静止画面的出帧空洞不算缺陷**,单独计数不计入;
- **内容突现**(C11)= 灰度差 > `--pop` × 运动帧差分中位数;冷启动闸要求无白/黑闪、无内容两跳突现。

`screenrecord` 是 VFR,基准帧间隔取 dt 中位数,不要当成固定 fps。

## analyze_frametimeline.py — C8 / 票 56

这台小米的 `SurfaceFlinger --timestats` 已卡死，禁止再跑 enable/clear。替代流程见
playbook T2：以 `android.surfaceflinger.frametimeline` 采 15 秒 trace，拉回后执行：

```bash
adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/s9.pb \
  < scripts/perf/frametimeline.cfg
# 在 15 秒内执行被测转场；不要调用 timestats enable/clear
adb pull /data/misc/perfetto-traces/s9.pb .
scripts/perf/.venv/bin/python scripts/perf/analyze_frametimeline.py s9.pb \
  --package com.chasel.ng2 --source device --vsync-ms 8.333
```

脚本将 app surface frame 与 actual display frame 按 `display_frame_token` 配对，以
SurfaceFlinger actual frame start→present 的峰形替代 C8：9–11ms 低峰与相隔一个
vsync 的 17–20ms 高峰同时达到 5% 即双峰。它不会把 app surface slice 的 `dur`
（只到 buffer ready/acquire fence）误当 latch2present。裁决前仍须独立满足 T1/T4/T7。

## 还没落盘的

Perfetto 侧的 `present_type='Dropped Frame'` 计数(C7)仍未独立落脚本；C8 已由
`analyze_frametimeline.py` 固化。

## 专项工具

以下工具没有 `--source` 参数，运行前自行核对真机、release、前台焦点、刷新率与采样窗口。前两个依赖 perfetto，滚动速度分析依赖 av + numpy，可复用上述 venv。

| 脚本 | 用途 | 必需参数 |
|---|---|---|
| `analyze_gpu_wait.py` | 分析 app HWUI / GPU 等待 | `trace --package <pkg>` |
| `analyze_s9_segments.py` | 按固定 S9 场景与输入事件分段分析 FrameTimeline | `trace --package <pkg>`（可加 `--by-peak`） |
| `analyze_scroll_velocity.py` | 从录屏估计纵向内容位移和速度 | `video --csv <输出文件>` |

完整选项用相应脚本的 `--help` 查看，S9 脚本的窗口逻辑只适用于对应采样场景；专项结果需结合性能手册判断。
