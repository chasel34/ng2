#!/usr/bin/env python3
"""Estimate vertical content velocity from a device screen recording.

The comparison is intentionally restricted to the scrolling body and uses three
per-row image signatures, so fixed top bars and small animated icons do not
dominate the displacement estimate.
"""
import argparse
import csv
from pathlib import Path

import av
import numpy as np


def decode(path: str, width: int):
    times, frames = [], []
    with av.open(path) as container:
        stream = container.streams.video[0]
        scale = width / stream.codec_context.width
        height = round(stream.codec_context.height * scale)
        for frame in container.decode(stream):
            times.append(float(frame.pts * stream.time_base))
            gray = frame.reformat(width=width, height=height, format="gray").to_ndarray().astype(np.float32)
            body = gray[round(height * 0.18):round(height * 0.93), :]
            signatures = np.column_stack((
                body.mean(axis=1),
                body.std(axis=1),
                np.abs(np.diff(body, axis=1)).mean(axis=1),
            ))
            frames.append(signatures)
    return np.asarray(times), frames, 1.0 / scale


def displacement(previous, current, max_shift):
    height = len(previous)
    lo, hi = round(height * 0.08), round(height * 0.92)
    errors = []
    shifts = range(-max_shift, max_shift + 1)
    for shift in shifts:
        if shift >= 0:
            a, b = previous[lo + shift:hi], current[lo:hi - shift]
        else:
            a, b = previous[lo:hi + shift], current[lo - shift:hi]
        errors.append(float(np.mean(np.abs(a - b))))
    best = int(np.argmin(errors))
    ranked = np.partition(np.asarray(errors), min(4, len(errors) - 1))
    quality = ranked[min(4, len(errors) - 1)] / max(errors[best], 1e-6)
    return shifts[best], quality


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("video")
    parser.add_argument("--csv", required=True)
    parser.add_argument("--width", type=int, default=305)
    parser.add_argument("--max-shift", type=int, default=70)
    args = parser.parse_args()

    times, frames, scale = decode(args.video, args.width)
    rows = []
    for index in range(1, len(frames)):
        shift, quality = displacement(frames[index - 1], frames[index], args.max_shift)
        dt = times[index] - times[index - 1]
        dy = shift * scale
        rows.append((index, times[index], dt * 1000, dy, dy / dt if dt > 0 else 0, quality))

    with Path(args.csv).open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(("frame", "time_s", "dt_ms", "dy_px", "speed_px_s", "quality"))
        writer.writerows(rows)

    valid = [row for row in rows if 0 < row[2] < 100 and row[3] > 0 and row[5] >= 1.01]
    speeds = np.asarray([row[4] for row in valid])
    print(f"frames={len(frames)} duration={times[-1] - times[0]:.3f}s valid_motion={len(valid)}")
    if len(speeds):
        p10, p50, p90 = np.percentile(speeds, (10, 50, 90))
        print(f"speed_px_s p10/p50/p90/max={p10:.0f}/{p50:.0f}/{p90:.0f}/{speeds.max():.0f}")
    bucket_start = np.floor(times[0] * 10) / 10
    while bucket_start <= times[-1]:
        bucket = [row[4] for row in valid if bucket_start <= row[1] < bucket_start + 0.1]
        if bucket:
            print(f"{bucket_start:6.2f}-{bucket_start + 0.1:6.2f}s median={np.median(bucket):7.0f} max={max(bucket):7.0f} n={len(bucket):2d}")
        bucket_start += 0.1


if __name__ == "__main__":
    main()
