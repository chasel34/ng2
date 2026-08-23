# 52 — P2:Android 16 framestats 全为 Flags=32,分析脚本过滤后无可用帧

**Status:** open

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
