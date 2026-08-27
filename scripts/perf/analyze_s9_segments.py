#!/usr/bin/env python3
"""Split the fixed scene-9 drawer/settings trace into interaction windows."""
import argparse

from perfetto.trace_processor import TraceProcessor


def percentile(values, percent):
    values = sorted(values)
    if not values:
        return 0.0
    position = (len(values) - 1) * percent / 100
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] * (upper - position) + values[upper] * (position - lower)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("trace")
    parser.add_argument("--package", required=True)
    args = parser.parse_args()
    tp = TraceProcessor(trace=args.trace)
    try:
        layers = list(tp.query("""
            select layer_name, count(*) n
              from actual_frame_timeline_slice
             where layer_name glob '*%s*'
             group by layer_name order by n desc
        """ % args.package.replace("'", "''")))
        layer = layers[0].layer_name.replace("'", "''")
        frames = list(tp.query("""
            select d.ts, d.dur
              from actual_frame_timeline_slice s
              join actual_frame_timeline_slice d
                on d.display_frame_token = s.display_frame_token
               and d.surface_frame_token is null
             where s.layer_name = '%s'
               and s.surface_frame_token is not null
               and s.present_type != 'Dropped Frame'
               and d.present_type != 'Dropped Frame'
        """ % layer))
        start, end = min(row.ts for row in frames), max(row.ts + row.dur for row in frames)
        inputs = [row.start_ts for row in tp.query("""
            select start_ts from process
             where name = 'input' and start_ts between %d and %d
             order by start_ts
        """ % (start, end))]
        gpu = list(tp.query("""
            select ts, dur from slice
             where name glob 'waiting for GPU completion*'
               and dur > 0 and ts between %d and %d
             order by ts
        """ % (start, end)))
    finally:
        tp.close()

    print(f"input commands: {len(inputs)}")
    if len(inputs) == 12:
        windows = [("settings-return", start, inputs[0])]
        windows += [
            (f"drawer-{index + 1}", inputs[index * 2], inputs[index * 2 + 2] if index < 5 else end)
            for index in range(6)
        ]
    elif len(inputs) == 13:
        labels = ("settings-return", "drawer-1", "drawer-2", "drawer-3", "drawer-4", "drawer-5", "drawer-6")
        cuts = (0, 1, 3, 5, 7, 9, 11, 13)
        windows = [
            (label, inputs[left], inputs[right] if right < len(inputs) else end)
            for label, left, right in zip(labels, cuts, cuts[1:])
        ]
    else:
        labels = ("drawer-1", "drawer-scroll-settings", "drawer-2", "drawer-3", "drawer-4", "drawer-5")
        cuts = (0, 2, 6, 8, 10, 12, 14)
        windows = [
            (label, inputs[left], inputs[right] if right < len(inputs) else end)
            for label, left, right in zip(labels, cuts, cuts[1:]) if left < len(inputs)
        ]
    for label, window_start, window_end in windows:
        window_frames = [row.dur / 1e6 for row in frames if window_start <= row.ts < window_end]
        window_gpu = [row.dur / 1e6 for row in gpu if window_start <= row.ts < window_end]
        high = [value for value in window_frames if 13.333 <= value <= 22.499]
        print(f"{label}: frames={len(window_frames)} high={len(high)} "
              f"({len(high) / len(window_frames) * 100 if window_frames else 0:.1f}%) "
              f"GPU p95={percentile(window_gpu, 95):.3f}ms max={max(window_gpu, default=0):.3f}ms")


if __name__ == "__main__":
    main()
