#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gfxinfo framestats 逐帧分析 — 判据编号见 docs/perf-playbook.md (C2 / C5 / C6 / T3 / T5 / T7 / T8)。

采样:
    adb shell dumpsys gfxinfo <pkg> reset
    # 操作。input swipe 单次时长必须 >80ms,起步段 ≤80ms 不作证据 (T5)
    adb shell dumpsys gfxinfo <pkg> framestats > fs.txt
分析:
    scripts/perf/analyze_framestats.py fs.txt --source device

列一律按表头名定位:新版 framestats 在 Flags 后插了 FrameTimelineVsyncId (T3)。

自检:
    scripts/perf/analyze_framestats.py --selftest
"""
import argparse
import sys

MS = 1e6  # ns -> ms

# hwui FrameInfoFlags(frameworks/base/libs/hwui/FrameInfo.h)。低 4 位含义自 Android 5 起没变过:
FLAG_WINDOW_LAYOUT_CHANGED = 1 << 0  # 窗口布局/尺寸变化的那一帧(含首帧),必然超时,不计入
FLAG_RT_ANIMATION = 1 << 1           # RenderThread 驱动的动画帧,是正常帧,要计入
FLAG_SURFACE_CANVAS = 1 << 2         # Surface.lockCanvas 软件帧,hwui 阶段时间戳残缺,不计入
FLAG_SKIPPED_FRAME = 1 << 3          # hwui 主动跳过的帧,时间戳是上一帧残留(会算出负数耗时),不计入
#
# bit4 及以上是新版本追加的位。Android 16 / API 36 真机(25113PN0EC,120Hz)上 bit5(=32)
# 几乎覆盖每一个交互/滚动帧:s3-native 120 帧里 119 帧是 32,而同一份 dump 的现代
# FrameTimeline 汇总是 6,318 帧 / 1 janky(0.02%)——32 不可能是「无效帧」标记,它是常态位
# (与手势/滚动窗口共现,静止段反而是 0;s4-native-fast 里 32 的连续段正好是那一次 swipe)。
# 因此这里不再按 `Flags != 0` 剔除,而是只把上面三位当无效帧,未知高位一律放行(票 52)。
INVALID_FLAGS = FLAG_WINDOW_LAYOUT_CHANGED | FLAG_SURFACE_CANVAS | FLAG_SKIPPED_FRAME

FLAG_NAMES = [
    (FLAG_WINDOW_LAYOUT_CHANGED, "WindowLayoutChanged"),
    (FLAG_RT_ANIMATION, "RTAnimation"),
    (FLAG_SURFACE_CANVAS, "SurfaceCanvas"),
    (FLAG_SKIPPED_FRAME, "SkippedFrame"),
]

# 计算 C5/C6 必须的列;缺一列直接报错,别拿半份表头算出「看着挺像」的数
REQUIRED_COLUMNS = ("Flags", "IntendedVsync", "HandleInputStart", "SwapBuffers", "FrameCompleted")


def flag_names(flags):
    """把 Flags 数值翻成可读名字,未知高位按 bitN 列出。"""
    names = [n for bit, n in FLAG_NAMES if flags & bit]
    rest = flags & ~sum(bit for bit, _ in FLAG_NAMES)
    names += ["bit%d" % i for i in range(64) if rest >> i & 1]
    return "+".join(names) if names else "无"


def is_invalid_frame(flags):
    """只有首帧/窗口变化、软件 canvas、跳过帧算无效;其余(含 API 36 的 bit5=32)都是正常帧。"""
    return bool(flags & INVALID_FLAGS)


def timestamps_sane(row):
    """跳过帧偶尔不带 SkippedFrame 位,只留下一帧的残留时间戳 —— 用单调性兜底。"""
    try:
        iv = int(row["IntendedVsync"])
        hi = int(row["HandleInputStart"])
        sb = int(row["SwapBuffers"])
        fc = int(row["FrameCompleted"])
    except (KeyError, ValueError):
        return False
    return iv > 0 and hi > 0 and sb >= hi and fc >= sb and hi >= iv

# 阶段拆分口径与 .scratch/perf-2026-08/report.md §2.3 的表一致
STAGES = [
    ("输入处理", "HandleInputStart", "AnimationStart"),
    ("动画", "AnimationStart", "PerformTraversalsStart"),
    ("测量+布局", "PerformTraversalsStart", "DrawStart"),
    ("录制绘制指令", "DrawStart", "SyncQueued"),
    ("同步等待", "SyncStart", "IssueDrawCommandsStart"),
    ("下发绘制指令", "IssueDrawCommandsStart", "SwapBuffers"),
    ("交换缓冲(等 GPU/合成,不归 app)", "SwapBuffers", "FrameCompleted"),
]


def parse_profiledata(text):
    """返回 (block_count, rows)。rows 为 dict,键取自各 block 自己的表头。

    每个 block 是一个 window/surface,自带一份表头(新版在 Flags 后插了 FrameTimelineVsyncId,
    所以只能按列名取值)。行里额外塞一个 `_block`,跨 window 的帧不能拿来算帧间隔。
    """
    inside, header, rows, blocks = False, None, [], 0
    for line in text.splitlines():
        line = line.strip()
        if line == "---PROFILEDATA---":
            inside = not inside
            if inside:
                blocks += 1
                header = None
            continue
        if not inside or not line:
            continue
        cells = [c for c in line.split(",") if c != ""]
        if header is None:
            header = cells
            continue
        if len(cells) == len(header):
            row = dict(zip(header, cells))
            row["_block"] = blocks
            rows.append(row)
    return blocks, rows


def pct(values, q):
    if not values:
        return float("nan")
    s = sorted(values)
    i = min(len(s) - 1, max(0, int(round((len(s) - 1) * q))))
    return s[i]


def fmt(values):
    return "p50 %.1f / p90 %.1f / p95 %.1f / p99 %.1f / max %.1f ms" % (
        pct(values, .50), pct(values, .90), pct(values, .95), pct(values, .99), pct(values, 1.0))


def selftest():
    """Flags 过滤 + 列解析的最小回归(票 52)。标准库跑,不引 pytest。"""
    # 两个 block:第一个是 API 36 表头(Flags 后插了 FrameTimelineVsyncId),第二个是老表头,
    # 且两块列顺序不同 —— 按下标取列必然错位,按列名取才对得上。
    new_hdr = ("Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,"
               "AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameStartTime,"
               "FrameInterval,WorkloadTarget,SyncQueued,SyncStart,IssueDrawCommandsStart,"
               "SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,")
    old_hdr = ("Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,"
               "AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,"
               "IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,"
               "QueueBufferDuration,")

    def new_row(flags, t, app_cpu_ns=2000000):
        v = t
        return ",".join(str(x) for x in [
            flags, 123456, v, v, 0, v + 100, v + 200, v + 300, v + 400, v + 8330000, v, 8330000,
            13670000, v + 500, v + 600, v + 700, v + 100 + app_cpu_ns, v + 100 + app_cpu_ns + 500000,
            10000, 300000, v + 100 + app_cpu_ns]) + ","

    def old_row(flags, t):
        return ",".join(str(x) for x in [
            flags, t, t, 0, 0, t + 100, t + 200, t + 300, t + 400, t + 500, t + 600, t + 700,
            t + 2000000, t + 2500000, 10000, 300000]) + ","

    base = 1000000000000
    step = 8330000
    lines = ["---PROFILEDATA---", new_hdr]
    flags_seq = [32, 32, 2, 32, 1, 32, 4, 32, 40, 32]  # 32/2 常态位应留下,1/4/40 应剔除
    for i, f in enumerate(flags_seq):
        lines.append(new_row(f, base + i * step))
    lines += ["---PROFILEDATA---", "", "---PROFILEDATA---", old_hdr,
              old_row(0, base + 100 * step), old_row(8, base + 101 * step), "---PROFILEDATA---"]
    blocks, rows = parse_profiledata("\n".join(lines))
    assert blocks == 2, blocks
    assert len(rows) == 12, len(rows)

    # 1) 列按名字取:新表头里 IntendedVsync 是第 3 列,老表头里是第 2 列,两块都要取对
    assert int(rows[0]["IntendedVsync"]) == base, rows[0]["IntendedVsync"]
    assert int(rows[0]["FrameTimelineVsyncId"]) == 123456
    assert int(rows[10]["IntendedVsync"]) == base + 100 * step, rows[10]["IntendedVsync"]
    assert "FrameTimelineVsyncId" not in rows[10]
    assert rows[0]["_block"] == 1 and rows[10]["_block"] == 2

    # 2) Flags 语义:0/2/32(含未知高位)是正常帧;1/4/8/40 是无效帧
    for f in (0, 2, 32, 34, 64, 96):
        assert not is_invalid_frame(f), f
    for f in (1, 4, 8, 12, 33, 40, 36):
        assert is_invalid_frame(f), f
    kept = [r for r in rows if not is_invalid_frame(int(r["Flags"]))]
    assert len(kept) == 8, len(kept)  # 新块 10 行剔掉 1/4/40 剩 7,老块 0 留下、8 剔除
    assert sorted({int(r["Flags"]) for r in kept}) == [0, 2, 32]

    # 3) 时间戳兜底:SwapBuffers 落在 HandleInputStart 之前(跳过帧残留)必须被剔除
    bad = dict(rows[0])
    bad["SwapBuffers"] = str(int(bad["IntendedVsync"]) - 1000000000)
    assert timestamps_sane(rows[0]) and not timestamps_sane(bad)

    # 4) 名字翻译
    assert flag_names(40) == "SkippedFrame+bit5", flag_names(40)
    assert flag_names(0) == "无"
    print("selftest OK:%d block / %d 行,Flags 过滤与按列名取值均正确" % (blocks, len(rows)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", nargs="?", default="-", help="framestats dump,默认读 stdin")
    ap.add_argument("--selftest", action="store_true", help="跑内置回归(Flags 过滤 + 按列名解析),不读文件")
    ap.add_argument("--source", choices=["device", "emulator", "unknown"], default="unknown",
                    help="采样来源。非 device 的数据一律不可用于性能裁决")
    ap.add_argument("--drop-ms", type=float, default=13.0,
                    help="IntendedVsync 间隔超过该值判丢帧,默认 13ms(120Hz 判据 C6)")
    ap.add_argument("--idle-ms", type=float, default=100.0,
                    help="IntendedVsync 间隔 ≥ 该值算静止/换阶段空档,不计丢帧,默认 100ms")
    ap.add_argument("--skip-head-ms", type=float, default=0.0, help="丢弃采样头部 N ms(注入手势起步段,T5)")
    ap.add_argument("--top", type=int, default=5, help="列出最差的 N 帧")
    a = ap.parse_args()
    if a.selftest:
        selftest()
        return

    text = sys.stdin.read() if a.file == "-" else open(a.file, encoding="utf-8", errors="replace").read()
    blocks, rows = parse_profiledata(text)
    if not rows:
        sys.exit("没有解析到 PROFILEDATA 行;确认 dump 里有 framestats 而不只是聚合统计")

    missing = [c for c in REQUIRED_COLUMNS if c not in rows[0]]
    if missing:
        sys.exit("表头缺列 %s;这不是 framestats 表(列一律按名字取,别按下标)" % ", ".join(missing))

    rows.sort(key=lambda r: int(r["IntendedVsync"]))
    total_rows = len(rows)
    dropped = {}  # 剔除原因 -> 帧数
    for r in rows:
        f = int(r["Flags"])
        if is_invalid_frame(f):
            dropped["Flags=%d(%s)" % (f, flag_names(f))] = dropped.get("Flags=%d(%s)" % (f, flag_names(f)), 0) + 1
        elif not timestamps_sane(r):
            dropped["时间戳不自洽"] = dropped.get("时间戳不自洽", 0) + 1
    rows = [r for r in rows if not is_invalid_frame(int(r["Flags"])) and timestamps_sane(r)]
    if a.skip_head_ms > 0 and rows:
        t0 = int(rows[0]["IntendedVsync"])
        before = len(rows)
        rows = [r for r in rows if (int(r["IntendedVsync"]) - t0) / MS >= a.skip_head_ms]
        dropped["起步段 <%.0fms(T5)" % a.skip_head_ms] = before - len(rows)
    kept_flags = {}
    for r in rows:
        f = int(r["Flags"])
        kept_flags[f] = kept_flags.get(f, 0) + 1
    if not rows:
        sys.exit("过滤后无可用帧(剔除明细:%s)" % (dropped or "无"))

    app_cpu = [(int(r["SwapBuffers"]) - int(r["HandleInputStart"])) / MS for r in rows]
    total = [(int(r["FrameCompleted"]) - int(r["IntendedVsync"])) / MS for r in rows]
    # 帧间隔只在同一个 window(block)内相邻两帧之间算,跨 window 的相邻行没有先后语义
    gaps_at = [(i, (int(rows[i]["IntendedVsync"]) - int(rows[i - 1]["IntendedVsync"])) / MS)
               for i in range(1, len(rows)) if rows[i]["_block"] == rows[i - 1]["_block"]]
    gaps = [g for _, g in gaps_at]
    intervals = [int(r["FrameInterval"]) / MS for r in rows if "FrameInterval" in r and int(r["FrameInterval"]) > 0]
    interval = pct(intervals, .50) if intervals else pct(gaps, .50)

    # 静止段(app 没内容要画,本来就不出帧)不是丢帧:间隔 ≥ --idle-ms 的空档单独计,不进 C6。
    # 真机数据里这两类是分得开的:一次卡顿的空档 13~60ms,静止/换阶段的空档 ≥100ms(票 52)。
    idle = [(i, g) for i, g in gaps_at if g >= a.idle_ms]
    drops = [(i, g) for i, g in gaps_at if a.drop_ms < g < a.idle_ms]
    missed = sum(max(1, int(round(g / interval)) - 1) for _, g in drops) if interval else len(drops)
    span = (int(rows[-1]["FrameCompleted"]) - int(rows[0]["IntendedVsync"])) / MS
    motion_span = span - sum(g for _, g in idle)

    print("== 来源 ==")
    if a.source == "device":
        print("  真机采样。仍须 release 包(R8):debug 包数据一律作废(T7)。")
    else:
        print("  来源=%s → 本次数据【不可用于性能裁决】,只证明脚本与采样链路可用。\n"
              "  模拟器 Janky frames 被 guest→host GL 管道淹没(X1);mActiveRenderFrameRate=60,验不了 120Hz(T8)。" % a.source)
    print("\n== 样本 ==")
    print("  PROFILEDATA block %d 个,原始 %d 帧,剔除 %d 帧,计入 %d 帧,跨度 %.0f ms"
          % (blocks, total_rows, total_rows - len(rows), len(rows), span))
    print("  剔除口径 Flags & %d(WindowLayoutChanged|SurfaceCanvas|SkippedFrame)+ 时间戳自洽;明细:%s"
          % (INVALID_FLAGS, "、".join("%s ×%d" % (k, v) for k, v in sorted(dropped.items()) if v) or "无"))
    print("  计入帧 Flags 分布:%s(bit4 及以上是新版常态位,放行)"
          % "、".join("%d(%s) ×%d" % (f, flag_names(f), n) for f, n in sorted(kept_flags.items())))
    print("  帧间隔基准 %.2f ms(≈%.0f Hz),丢帧阈值 IntendedVsync 间隔 > %.1f ms"
          % (interval, 1000.0 / interval if interval else 0, a.drop_ms))
    if interval > a.drop_ms:
        print("  ! 基准间隔 > 阈值:本机不在 120Hz(13ms 阈值是 120Hz 判据),此处会把每一帧都判成丢帧;\n"
              "    换机口径请用 --drop-ms(约 1.5× 帧间隔 = %.1f ms)。" % (1.5 * interval))
    print("\n== app 每帧 CPU(HandleInputStart→SwapBuffers,判据 C5)==\n  " + fmt(app_cpu))
    print("\n== 整帧(IntendedVsync→FrameCompleted)==\n  " + fmt(total))
    print("\n== 丢帧(判据 C6)==")
    print("  运动段 %.0f ms(总跨度 %.0f ms 减去 %d 处 ≥%.0fms 的静止空档),间隔 %.1f~%.0f ms 的空档 %d 处,"
          "估算漏掉 %d 个 vsync,占运动段应出帧的 %.2f%%"
          % (motion_span, span, len(idle), a.idle_ms, a.drop_ms, a.idle_ms, len(drops), missed,
             100.0 * missed / max(1, len(rows) + missed)))
    for i, g in drops[:a.top]:
        print("    #%d  间隔 %.1f ms(≈%d 个 vsync)@ +%.0f ms"
              % (i, g, round(g / interval) if interval else 0,
                 (int(rows[i]["IntendedVsync"]) - int(rows[0]["IntendedVsync"])) / MS))
    if len(drops) > a.top:
        print("    ... 另有 %d 处" % (len(drops) - a.top))
    if idle:
        print("  静止空档(不计丢帧,app 没内容要画时本来就不出帧):%s"
              % "、".join("%.0f ms @ +%.0f ms" % (g, (int(rows[i]["IntendedVsync"]) - int(rows[0]["IntendedVsync"])) / MS)
                         for i, g in idle[:a.top]))
    print("  注:framestats 分不出「动画窗口内」和「静止画面」,>%.0fms 的空档一律按静止处理;\n"
          "     感知层 ground truth 仍是录屏逐帧(C1),系统侧对照是 gfxinfo 的 FrameTimeline janky 汇总。" % a.idle_ms)
    print("\n== 逐阶段均值 ==")
    for name, s, e in STAGES:
        if s in rows[0] and e in rows[0]:
            vals = [(int(r[e]) - int(r[s])) / MS for r in rows]
            print("  %-34s %6.2f ms" % (name, sum(vals) / len(vals)))
    print("\n== 最差 %d 帧(按 app CPU)==" % a.top)
    for c, t, r in sorted(zip(app_cpu, total, rows), key=lambda x: -x[0])[:a.top]:
        print("  app %6.2f ms / 整帧 %6.2f ms @ +%.0f ms"
              % (c, t, (int(r["IntendedVsync"]) - int(rows[0]["IntendedVsync"])) / MS))


if __name__ == "__main__":
    main()
