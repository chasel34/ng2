# 52 — P2:Android 16 framestats 全为 Flags=32,分析脚本过滤后无可用帧

**Status:** resolved

**Severity:** P2(C5/C6 自动分析不可用,票 19 只能退回 gfxinfo 现代 FrameTimeline 汇总与原始表人工取证)

## 现象

票 19 场景 3 在真机 `25113PN0EC`(Android 16 / SDK 36,120Hz)采集:

```bash
adb -s 192.168.0.101:40039 shell dumpsys gfxinfo com.chasel.ng2.n framestats
python3 scripts/perf/analyze_framestats.py s3-native-framestats.txt --source device
```

脚本输出:

```text
过滤后无可用帧
```

原始 `---PROFILEDATA---` 表头正常,包含 Android 新版插入的 `FrameTimelineVsyncId`,
但运动窗口内所有行的 `Flags` 均为 `32`(尾帧有 `40`),脚本按 `Flags != 0` 全部剔除。
同一份原始文件的系统现代 FrameTimeline 汇总明确有 6,318 帧、1 janky(0.02%),
不是没有渲染帧。

## 复现证据

- `.scratch/native-rewrite/acceptance/perf/s3-native-framestats.txt`
- 设备滚动中 `renderFrameRate=120.00001`,且
  `frameRateOverride {uid=10375 frameRateHz=120.00001}`。
- 前台焦点为 `com.chasel.ng2.n/com.chasel.ng2n.MainActivity`。

## 期望

明确 API 36 `Flags=32/40` 的语义,仅过滤真正的首帧/窗口变化无效帧,使
`analyze_framestats.py` 能对该真机输出 C5 app CPU、C6 IntendedVsync 丢帧与最差帧。
修复前票 19 不修改脚本,保留原始 framestats,场景闸以 C12 现代 janky 与 C1 录屏为主。

## 修复(2026-08-24)

### Flags 语义结论

hwui `FrameInfoFlags`(`frameworks/base/libs/hwui/FrameInfo.h`)只有低 4 位语义稳定,且从 Android 5 起没变过:

| 位 | 值 | 含义 | 是否无效帧 |
|---|---|---|---|
| bit0 | 1 | `WindowLayoutChanged` — 窗口布局/尺寸变化的那一帧(含首帧) | 是,必然超时,剔除 |
| bit1 | 2 | `RTAnimation` — RenderThread 驱动的动画帧 | **否,正常帧,计入** |
| bit2 | 4 | `SurfaceCanvas` — `Surface.lockCanvas` 软件帧,hwui 阶段时间戳残缺 | 是,剔除 |
| bit3 | 8 | `SkippedFrame` — hwui 主动跳过,时间戳是上一帧残留 | 是,剔除 |
| bit4+ | ≥16 | 新版本追加的位,API 36 上见到 bit5(=32) | **否,常态位,放行** |

bit5(=32)是常态位,数值行为三条实锤:

1. **覆盖面**:s3-native 120 帧里 119 帧是 32,同一份 dump 的现代 FrameTimeline 汇总是
   `Total frames rendered: 6318 / Janky frames: 1 (0.02%) / Number Missed Vsync: 0`
   ——32 不可能是「无效帧」标记,否则系统自己也不会把这些帧算成好帧。
2. **与手势共现**:s4-native-fast 里 32 是中间一段连续 38 帧,正好对上那一次 swipe,前后静止段是 0;
   s6-native 则是「32(进入)→ 2 RTAnimation(动画段)→ 32(退出)」。它标记的是帧的来源/调度状态,
   与耗时无关(flag 0 与 flag 32 两组的 app CPU、整帧耗时同量级)。
3. **同机对照**:同一台机器上 anzong(s3)整份采样是 0,s2 一段是 32 —— 是逐帧状态,不是机型/系统的固定标记。

尾帧的 `40` = `32 | 8` = 常态位 + `SkippedFrame`;这类行的时间戳是上一帧残留,
`SwapBuffers` 比 `IntendedVsync` 早 ~1s,直接算会得到 −1021ms 的 app CPU,必须剔除。

### 改动

- `scripts/perf/analyze_framestats.py`
  - 过滤口径由 `Flags != 0` 改为 `Flags & 13`(`WindowLayoutChanged|SurfaceCanvas|SkippedFrame`),
    未知高位一律放行;新增 `flag_names()` / `is_invalid_frame()`,输出里打印剔除明细与计入帧的 Flags 分布。
  - 增加时间戳单调性兜底(`IntendedVsync ≤ HandleInputStart ≤ SwapBuffers ≤ FrameCompleted`),
    挡掉不带 `SkippedFrame` 位但只有残留时间戳的行。
  - 列解析:仍按表头名取值(T3),另加 `REQUIRED_COLUMNS` 缺列即报错;每行记 `_block`,
    帧间隔只在同一 window 内相邻两帧之间算。
  - C6 只在运动段内算:新增 `--idle-ms`(默认 100ms),≥ 该值的空档判为「app 没内容要画」,
    单独列出不计丢帧。真机数据里两类空档分得很开——卡顿 13~60ms,静止 ≥100ms(常见几百 ms 到几十秒)。
    不做这层,s3-anzong 那种 25 帧 / 33.5s 的采样会算出 3,994 个「丢帧」(15,976%)。
  - 新增 `--selftest`(纯标准库,`python3 scripts/perf/analyze_framestats.py --selftest`):
    覆盖两种表头(API 36 带 `FrameTimelineVsyncId` / 旧版不带、且列序不同)按名取列、
    Flags 白名单/黑名单、跳过帧时间戳兜底、Flags 名字翻译。
- `scripts/perf/README.md`:补「Flags 怎么过滤」一节与新选项。
- `docs/perf-playbook.md`:新增陷阱 **T14**(Flags 不能按「非 0 即无效」过滤),C6 补运动段口径。

### 回归(仓库内已有真机采样,全部 `--source device`)

| 文件 | 计入/原始帧 | 跨度→运动段 | 基准间隔 | C5 app 每帧 CPU(p50/p95/max) | C6 丢帧 | 最差帧 app/整帧 |
|---|---|---|---|---|---|---|
| s3-native | 119/120(剔 1 = Flags 40) | 1010→1010 ms | 8.32 ms(120Hz) | 1.6 / 3.4 / 4.4 ms | 2 处 / 3 vsync / 2.46% | 4.40 / 9.10 ms |
| s3-rn | 118/120 | 982→982 ms | 8.32 ms | 2.9 / 3.6 / 8.3 ms | 0 处 / 0 / 0.00% | 8.29 / 9.94 ms |
| s3-anzong | 25/25 | 33533→14 ms | 8.34 ms | 1.8 / 3.0 / 3.5 ms | 0 处 / 0 / 0.00% | 3.46 / 16.17 ms |
| s4-native-fast | 120/120 | 1736→996 ms | 8.32 ms | 1.9 / 2.9 / 3.3 ms | 1 处 / 1 / 0.83% | 3.29 / 16.54 ms |
| s4-rn-fast | 120/120 | 2178→974 ms | 8.30 ms | 2.9 / 4.9 / 6.0 ms | 0 处 / 0 / 0.00% | 5.96 / 10.88 ms |
| s4-native-slow | 120/120 | 1000→1000 ms | 8.32 ms | 2.1 / 2.6 / 3.6 ms | 0 处 / 0 / 0.00% | 3.59 / 10.44 ms |
| s4-rn-slow | 119/120 | 1389→982 ms | 8.32 ms | 2.6 / 3.7 / 5.5 ms | 0 处 / 0 / 0.00% | 5.45 / 7.15 ms |
| s5-native | 120/120 | 2253→999 ms | 8.32 ms | 2.1 / 3.7 / 14.8 ms | 1 处 / 1 / 0.83% | 14.78 / 17.39 ms |
| s5-rn | 120/120 | 2199→1070 ms | 8.32 ms | 4.3 / 7.6 / 47.1 ms | 8 处 / 10 / 7.69% | 47.06 / 55.38 ms |
| s6-native | 120/120 | 1079→1079 ms | 8.32 ms | 4.7 / 5.2 / 5.8 ms | 1 处 / 10 / 7.69% | 5.79 / 7.97 ms |
| s6-rn | 120/120 | 1178→986 ms | 8.32 ms | 4.6 / 5.1 / 6.0 ms | 0 处 / 0 / 0.00% | 6.05 / 10.48 ms |
| s6-anzong | 120/120 | 1169→986 ms | 8.32 ms | 4.3 / 5.0 / 5.6 ms | 0 处 / 0 / 0.00% | 5.61 / 13.15 ms |
| s2-native | 89/89 | 5758→921 ms | 8.32 ms | 2.5 / 8.2 / 42.2 ms | 14 处 / 22 / 19.82% | 42.23 / 43.36 ms |
| s2-rn(60Hz,`--drop-ms 25`) | 58/59 | 2272→976 ms | 16.64 ms | 4.1 / 10.8 / 23.9 ms | 3 处 / 3 / 4.92% | 23.89 / 26.61 ms |

修复前:上表 14 份里有 9 份按旧口径 `Flags == 0` 一帧不剩、直接退出「过滤后无可用帧」
(s2-native、s2-rn、s3-native、s3-rn、s4-rn-fast、s4-rn-slow、s5-native、s5-rn、s6-rn);
剩下 5 份也被砍到只剩零星帧(s6-native 3/120、s6-anzong 3/120、s4-native-slow 40/120、
s4-native-fast 82/120、s3-anzong 25/25),其中 s6 两份的 3 帧样本根本不足以出 p95。

**与系统口径对拍(场景 3 native)**:脚本给出的是采样窗口末段 1,010 ms 的 119 帧、2 处空档、
估算漏 3 个 vsync;同一份 dump 的整会话 FrameTimeline 汇总是 6,318 帧 / 1 janky /
`Number Missed Vsync: 0` / `Number Frame deadline missed: 1`。两者同量级(个位数),
没有出现修复方向搞错时才会有的成百上千丢帧。

`python3 scripts/perf/analyze_framestats.py --selftest` → `selftest OK:2 block / 12 行`。
