#!/usr/bin/env python3
"""Summarize HWUI GPU fence waits from a rich Perfetto trace."""
import argparse
import statistics

from perfetto.trace_processor import TraceProcessor


def percentile(values, percent):
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] * (1 - fraction) + ordered[upper] * fraction


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("trace")
    parser.add_argument("--package", required=True)
    args = parser.parse_args()
    tp = TraceProcessor(trace=args.trace)
    try:
        bounds = list(tp.query("""
            select min(ts) start_ts, max(ts + dur) end_ts
              from actual_frame_timeline_slice
             where layer_name glob '*%s*'
               and surface_frame_token is not null
        """ % args.package.replace("'", "''")))[0]
        rows = list(tp.query("""
            select s.ts, s.dur, s.name, t.name thread_name
              from slice s
              join thread_track tt on tt.id = s.track_id
              join thread t on t.utid = tt.utid
              join process p on p.upid = t.upid
             where p.name glob '%s*'
               and s.name glob 'waiting for GPU completion*'
               and s.dur > 0
             order by s.ts
        """ % args.package.replace("'", "''")))
        if not rows:
            # The HWUI fence is sometimes attached to a graphics/process track
            # rather than RenderThread's thread_track. The rich config enables
            # app atrace for this package only, so these slices remain app HWUI.
            rows = list(tp.query("""
                select ts, dur, name, '' thread_name
                  from slice
                 where name glob 'waiting for GPU completion*'
                   and dur > 0
                   and ts between %d and %d
                 order by ts
            """ % (bounds.start_ts, bounds.end_ts)))
    finally:
        tp.close()
    durations = [row.dur / 1e6 for row in rows]
    if not durations:
        raise SystemExit("no app waiting-for-GPU-completion slices")
    print(f"GPU fence frames: {len(durations)}")
    print(f"GPU wait ms p50/p90/p95/max: {statistics.median(durations):.3f}/"
          f"{percentile(durations, 90):.3f}/{percentile(durations, 95):.3f}/{max(durations):.3f}")
    print(f">6ms: {sum(value > 6 for value in durations)} ({sum(value > 6 for value in durations) / len(durations) * 100:.1f}%)")
    print(f">8.333ms: {sum(value > 8.333 for value in durations)}")


if __name__ == "__main__":
    main()
