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
"""
import argparse
import sys

MS = 1e6  # ns -> ms

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
    """返回 (block_count, rows)。rows 为 dict,键取自各 block 自己的表头。"""
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
            rows.append(dict(zip(header, cells)))
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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", nargs="?", default="-", help="framestats dump,默认读 stdin")
    ap.add_argument("--source", choices=["device", "emulator", "unknown"], default="unknown",
                    help="采样来源。非 device 的数据一律不可用于性能裁决")
    ap.add_argument("--drop-ms", type=float, default=13.0,
                    help="IntendedVsync 间隔超过该值判丢帧,默认 13ms(120Hz 判据 C6)")
    ap.add_argument("--skip-head-ms", type=float, default=0.0, help="丢弃采样头部 N ms(注入手势起步段,T5)")
    ap.add_argument("--top", type=int, default=5, help="列出最差的 N 帧")
    a = ap.parse_args()

    text = sys.stdin.read() if a.file == "-" else open(a.file, encoding="utf-8", errors="replace").read()
    blocks, rows = parse_profiledata(text)
    if not rows:
        sys.exit("没有解析到 PROFILEDATA 行;确认 dump 里有 framestats 而不只是聚合统计")

    rows.sort(key=lambda r: int(r["IntendedVsync"]))
    total_rows = len(rows)
    flagged = [r for r in rows if int(r["Flags"]) != 0]
    rows = [r for r in rows if int(r["Flags"]) == 0]  # Flags!=0 是首帧/窗口变更帧,不计入
    if a.skip_head_ms > 0 and rows:
        t0 = int(rows[0]["IntendedVsync"])
        rows = [r for r in rows if (int(r["IntendedVsync"]) - t0) / MS >= a.skip_head_ms]
    if not rows:
        sys.exit("过滤后无可用帧")

    app_cpu = [(int(r["SwapBuffers"]) - int(r["HandleInputStart"])) / MS for r in rows]
    total = [(int(r["FrameCompleted"]) - int(r["IntendedVsync"])) / MS for r in rows]
    gaps = [(int(rows[i]["IntendedVsync"]) - int(rows[i - 1]["IntendedVsync"])) / MS
            for i in range(1, len(rows))]
    intervals = [int(r["FrameInterval"]) / MS for r in rows if "FrameInterval" in r and int(r["FrameInterval"]) > 0]
    interval = pct(intervals, .50) if intervals else pct(gaps, .50)

    drops = [(i, g) for i, g in enumerate(gaps, start=1) if g > a.drop_ms]
    missed = sum(max(1, int(round(g / interval)) - 1) for _, g in drops) if interval else len(drops)
    span = (int(rows[-1]["FrameCompleted"]) - int(rows[0]["IntendedVsync"])) / MS

    print("== 来源 ==")
    if a.source == "device":
        print("  真机采样。仍须 release 包(R8):debug 包数据一律作废(T7)。")
    else:
        print("  来源=%s → 本次数据【不可用于性能裁决】,只证明脚本与采样链路可用。\n"
              "  模拟器 Janky frames 被 guest→host GL 管道淹没(X1);mActiveRenderFrameRate=60,验不了 120Hz(T8)。" % a.source)
    print("\n== 样本 ==")
    print("  PROFILEDATA block %d 个,原始 %d 帧,Flags!=0 剔除 %d 帧,计入 %d 帧,跨度 %.0f ms"
          % (blocks, total_rows, len(flagged), len(rows), span))
    print("  帧间隔基准 %.2f ms(≈%.0f Hz),丢帧阈值 IntendedVsync 间隔 > %.1f ms"
          % (interval, 1000.0 / interval if interval else 0, a.drop_ms))
    if interval > a.drop_ms:
        print("  ! 基准间隔 > 阈值:本机不在 120Hz(13ms 阈值是 120Hz 判据),此处会把每一帧都判成丢帧;\n"
              "    换机口径请用 --drop-ms(约 1.5× 帧间隔 = %.1f ms)。" % (1.5 * interval))
    print("\n== app 每帧 CPU(HandleInputStart→SwapBuffers,判据 C5)==\n  " + fmt(app_cpu))
    print("\n== 整帧(IntendedVsync→FrameCompleted)==\n  " + fmt(total))
    print("\n== 丢帧(判据 C6)==")
    print("  间隔 >%.1fms 的空档 %d 处,估算漏掉 %d 个 vsync,占比 %.2f%%"
          % (a.drop_ms, len(drops), missed, 100.0 * missed / max(1, len(rows))))
    for i, g in drops[:a.top]:
        print("    #%d  间隔 %.1f ms(≈%d 个 vsync)@ +%.0f ms"
              % (i, g, round(g / interval) if interval else 0,
                 (int(rows[i]["IntendedVsync"]) - int(rows[0]["IntendedVsync"])) / MS))
    if len(drops) > a.top:
        print("    ... 另有 %d 处" % (len(drops) - a.top))
    print("  注:framestats 分不出「动画窗口内」和「静止画面」,单看会误判;感知层 ground truth 是录屏逐帧(C1)。")
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
