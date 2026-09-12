#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Perfetto FrameTimeline 的 C8 单峰/双峰分析（票 56）。

Android 16 的 ``actual_frame_timeline_slice`` 不直接暴露 SurfaceFrame 的
``lastLatchTime``。本脚本把 app surface frame 通过 ``display_frame_token`` 配到
actual display frame，使用 display slice 的 ``dur``（SurfaceFlinger actual frame
start → present）判断是否存在相差一个 vsync 的两簇。它不是 app surface slice 的
``dur``，后者只到 buffer ready/acquire fence，不能冒充 latch2present。
"""

import argparse
from collections import Counter
from statistics import median
import sys

try:
    from perfetto.trace_processor import TraceProcessor
except ImportError:  # pragma: no cover - 环境提示
    TraceProcessor = None


def sql_quote(value):
    return "'" + value.replace("'", "''") + "'"


def classify(durations_ms, vsync_ms, min_secondary=0.05):
    """按相差一档 vsync 的低/高延迟簇裁为 single/double。"""
    split = 1.6 * vsync_ms
    ceiling = 2.7 * vsync_ms
    low = [v for v in durations_ms if 0.5 * vsync_ms <= v < split]
    high = [v for v in durations_ms if split <= v <= ceiling]
    other = len(durations_ms) - len(low) - len(high)
    need = max(5, round(len(durations_ms) * min_secondary))
    if len(low) >= need and len(high) >= need:
        verdict = "double"
    elif len(low) >= max(1, round(len(durations_ms) * 0.90)):
        verdict = "single-low"
    elif len(high) >= max(1, round(len(durations_ms) * 0.90)):
        verdict = "single-high"
    else:
        verdict = "indeterminate"
    return verdict, low, high, other, split, ceiling


def infer_vsync_ms(presents_ns):
    deltas = [(b - a) / 1e6 for a, b in zip(presents_ns, presents_ns[1:])]
    plausible = [v for v in deltas if 3.0 <= v <= 25.0]
    if not plausible:
        raise ValueError("没有可用的相邻 present 时间，使用 --vsync-ms 显式指定")
    # app 静止/跳帧会产生 16/24ms 倍频；下半部中位数稳定落在物理 vsync。
    plausible.sort()
    lower = plausible[: max(3, (len(plausible) + 1) // 2)]
    return float(median(lower))


def selftest():
    assert classify([9.8] * 95 + [18.1] * 5, 8.33)[0] == "double"
    assert classify([9.8] * 99 + [12.0], 8.33)[0] == "single-low"
    assert classify([18.1] * 99 + [22.0], 8.33)[0] == "single-high"
    assert abs(infer_vsync_ms([0, 8_330_000, 16_660_000, 33_320_000]) - 8.33) < 0.01
    print("selftest: ok")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("trace", nargs="?", help="Perfetto .pb trace")
    ap.add_argument("--package", required=False, help="目标包名，用于选择 app layer")
    ap.add_argument("--layer", help="精确 layer 名；省略时选包含包名且帧数最多的 layer")
    ap.add_argument("--source", choices=["device", "emulator", "unknown"], default="unknown")
    ap.add_argument("--vsync-ms", type=float, help="显式刷新周期；120Hz 为 8.333ms")
    ap.add_argument("--min-secondary", type=float, default=0.05,
                    help="次峰至少占比，默认 0.05")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        selftest()
        return
    if not a.trace or not a.package:
        ap.error("trace 与 --package 必填（或使用 --selftest）")
    if TraceProcessor is None:
        sys.exit("缺少 perfetto：scripts/perf/.venv/bin/pip install perfetto")

    tp = TraceProcessor(trace=a.trace)
    try:
        layer = a.layer
        if not layer:
            package_glob = sql_quote("*" + a.package + "*")
            layers = list(tp.query(
                "select layer_name, count(*) n from actual_frame_timeline_slice "
                f"where layer_name glob {package_glob} group by layer_name order by n desc"
            ))
            if not layers:
                sys.exit("trace 中找不到包含目标包名的 actual surface layer")
            layer = layers[0].layer_name

        qlayer = sql_quote(layer)
        rows = list(tp.query(f"""
            select s.ts surface_ts,
                   s.dur surface_dur,
                   d.ts display_ts,
                   d.dur display_dur,
                   d.ts + d.dur display_present_ts,
                   s.present_type surface_present_type,
                   d.present_type display_present_type,
                   s.display_frame_token token
              from actual_frame_timeline_slice s
              join actual_frame_timeline_slice d
                on d.display_frame_token = s.display_frame_token
               and d.surface_frame_token is null
             where s.layer_name = {qlayer}
               and s.surface_frame_token is not null
             order by s.ts
        """))
    finally:
        tp.close()

    if len(rows) < 10:
        sys.exit(f"配对帧仅 {len(rows)}，不足以判峰")

    raw_surface_types = Counter(r.surface_present_type for r in rows)
    raw_display_types = Counter(r.display_present_type for r in rows)
    usable = [r for r in rows if r.surface_present_type != "Dropped Frame"
              and r.display_present_type != "Dropped Frame" and r.display_dur > 0]
    presents = [r.display_present_ts for r in usable]
    vsync_ms = a.vsync_ms or infer_vsync_ms(presents)
    durations = [r.display_dur / 1e6 for r in usable]
    verdict, low, high, other, split, ceiling = classify(
        durations, vsync_ms, a.min_secondary
    )

    p2p = [(b - x) / 1e6 for x, b in zip(presents, presents[1:])]
    p2p_near = [v for v in p2p if 0.5 * vsync_ms <= v <= 2.5 * vsync_ms]
    bins = Counter(round(v) for v in durations)
    present_types = Counter(r.display_present_type for r in usable)

    print("== 来源 ==")
    if a.source == "device":
        print("  真机 Perfetto FrameTimeline；仍须独立满足 release(T7)、前台(T4)、120Hz(T1)。")
    else:
        print(f"  来源={a.source} → 本次数据【不可用于性能裁决】(T8)。")
    print("\n== 配对 ==")
    print(f"  layer: {layer}")
    print(f"  actual surface→display token: {len(rows)}；可用 presented 帧: {len(usable)}")
    print(f"  surface present_type: {dict(raw_surface_types)}；"
          f"display present_type: {dict(raw_display_types)}")
    print("  指标: actual display frame start→present（C8 Perfetto 等价峰形；非 app surface dur）")
    print(f"  推定 vsync: {vsync_ms:.3f} ms；present2present 中位数: "
          f"{median(p2p_near):.3f} ms" if p2p_near else "  present2present: 无可用值")

    print("\n== 1ms 直方图 ==")
    print("  " + " ".join(f"{ms}ms={n}" for ms, n in sorted(bins.items())))
    print("\n== 单峰/双峰(C8) ==")
    print(f"  低簇 [{0.5 * vsync_ms:.1f},{split:.1f})ms: {len(low)} "
          f"({len(low) / len(durations) * 100:.1f}%), 中位 {median(low):.3f}ms" if low else
          f"  低簇: 0")
    print(f"  高簇 [{split:.1f},{ceiling:.1f}]ms: {len(high)} "
          f"({len(high) / len(durations) * 100:.1f}%), 中位 {median(high):.3f}ms" if high else
          f"  高簇: 0")
    print(f"  其他: {other}；可用帧 display present_type: {dict(present_types)}")
    labels = {
        "double": "双峰【不通过】：两簇相差约一个 vsync，队列深度在两档间振荡",
        "single-low": "低延迟单峰【通过 C8】",
        "single-high": "高延迟单峰【不按低延迟单峰通过；先排除 T1，再审队列深度】",
        "indeterminate": "无法裁决：样本未形成稳定单峰或一档 vsync 双峰",
    }
    print(f"  裁决: {labels[verdict]}")


if __name__ == "__main__":
    main()
