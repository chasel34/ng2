#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""录屏逐帧分析 — 感知层 ground truth,判据编号见 docs/perf-playbook.md (C1 / C10 / C11 / T5 / T7 / T8)。

帧间 dt(按 pts)+ 下采样灰度差分:
  「停格」= 运动窗口内的 dt 大洞;「内容突现」= 灰度 diff 爆点;
  静止画面的出帧空洞【不算缺陷】——只在运动窗口内判,且**不含窗口的第一个 dt**
  (那一格跨的是「最后一帧静止画面 → 动画首帧」,即点击到起步的延迟,见票 53)。

采样:
    adb shell screenrecord --time-limit 8 /sdcard/rec.mp4 &
    # 操作(input swipe 单次 >80ms,起步段不作证据 T5)
    adb pull /sdcard/rec.mp4
分析:
    scripts/perf/.venv/bin/python scripts/perf/analyze_rec.py rec.mp4 --source device
"""
import argparse
import sys

import av
import numpy as np


def decode(path, width):
    """返回 (ts[s], gray[N,h,w])。ts 取 pts,没有 pts 的帧按序号补。"""
    ts, frames = [], []
    with av.open(path) as c:
        st = c.streams.video[0]
        tb = float(st.time_base)
        h = max(8, round(width * (st.codec_context.height / st.codec_context.width)))
        for i, f in enumerate(c.decode(st)):
            ts.append(float(f.pts) * tb if f.pts is not None else float(i))
            frames.append(f.reformat(width=width, height=h, format="gray").to_ndarray())
    return np.asarray(ts, dtype=np.float64), np.asarray(frames, dtype=np.float32)


def runs(mask, gap):
    """mask 里的连续 True 段(允许 gap 帧断口),返回 [(start, end)] 闭区间。"""
    out = []
    i, n = 0, len(mask)
    while i < n:
        if not mask[i]:
            i += 1
            continue
        j = i
        k = i
        while k < n:
            if mask[k]:
                j = k
            elif k - j > gap:
                break
            k += 1
        out.append((i, j))
        i = j + 1
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--source", choices=["device", "emulator", "unknown"], default="unknown")
    ap.add_argument("--width", type=int, default=64, help="灰度下采样宽度,默认 64")
    ap.add_argument("--motion", type=float, default=1.0, help="判为「画面在动」的灰度差阈值(0-255),默认 1.0")
    ap.add_argument("--stall", type=float, default=1.9, help="dt 超过基准的多少倍算停格,默认 1.9(≈掉 1 个 vsync)")
    ap.add_argument("--pop", type=float, default=6.0, help="diff 超过运动中位数的多少倍算内容突现,默认 6.0")
    ap.add_argument("--gap", type=int, default=3, help="运动窗口允许的断口帧数,默认 3")
    a = ap.parse_args()

    ts, gray = decode(a.file, a.width)
    if len(ts) < 3:
        sys.exit("帧数太少,确认录屏文件有效")
    dt = np.diff(ts)
    diff = np.abs(np.diff(gray, axis=0)).mean(axis=(1, 2))
    base = float(np.median(dt))
    moving = diff > a.motion
    windows = runs(moving, a.gap)
    mid = float(np.median(diff[moving])) if moving.any() else 0.0

    print("== 来源 ==")
    if a.source == "device":
        print("  真机采样。仍须 release 包:debug 包数据一律作废(T7)。")
    else:
        print("  来源=%s → 本次数据【不可用于性能裁决】,只证明脚本与采样链路可用(T8)。" % a.source)
    print("\n== 样本 ==")
    print("  %s:%d 帧,%.2f s,基准帧间隔 %.2f ms(≈%.1f fps,VFR 录屏取中位数)"
          % (a.file, len(ts), ts[-1] - ts[0], base * 1000, 1.0 / base if base else 0))
    print("  运动窗口 %d 段,覆盖 %d/%d 帧;运动帧灰度差中位数 %.2f"
          % (len(windows), int(moving.sum()), len(diff), mid))

    stall_ms = a.stall * base * 1000
    # 每个运动窗口的**第一个 dt** 不是停格:它跨的是「最后一帧静止画面 → 动画第一帧」,
    # 也就是点击到动画起步的延迟,期间屏幕一动不动(票 53)。
    #
    # screenrecord 是 VFR,画面不变就不出帧;而真机点一下会把屏幕顶到 120Hz 保持
    # 约 600ms(touch boost),boost 退了就彻底停帧。于是每个「点击 → 动画」都在
    # 动画首帧前留一个 60–210ms 的空洞——原生 / RN / anzong 三个包全都有,量的是
    # `adb shell input tap` 的到达延迟,不是动画卡了。
    #
    # 它算不算进窗口只差一帧:boost 退出后恢复的第一帧若恰好已是动画首帧,窗口就从
    # 空洞那一格起算,空洞被误判成停格;若恢复的第一帧还是静止画面(动画晚一个 vsync
    # 起步),空洞就落在窗口外。票 53 原生 21 处 / anzong 0 处的差距全部来自这一帧,
    # 两边的动画本身 max dt 分别是 17.7ms 与 13.8ms,没有可见差。
    starts = {s for s, _ in windows}
    stalls, idle, leads = [], 0, []
    for i, d in enumerate(dt):
        if d * 1000 <= stall_ms:
            continue
        if i in starts:
            leads.append((i, d * 1000))
        elif any(s <= i <= e for s, e in windows):
            stalls.append((i, d * 1000))
        else:
            idle += 1
    print("\n== 停格(判据 C10:运动窗口内 dt > %.1f ms)==" % stall_ms)
    print("  运动窗口内 %d 处;静止画面出帧空洞 %d 处【不算缺陷,不计入】" % (len(stalls), idle))
    for i, d in stalls[:10]:
        print("    t=%.3fs  dt=%.1f ms(≈%.1f 帧)" % (ts[i], d, d / 1000 / base))
    if len(stalls) > 10:
        print("    ... 另有 %d 处" % (len(stalls) - 10))
    print("  动画起步前的静止空洞 %d 处【同属静止画面,不算缺陷】" % len(leads))
    if leads:
        print("    %.1f–%.1f ms,中位数 %.1f ms"
              % (min(d for _, d in leads), max(d for _, d in leads),
                 float(np.median([d for _, d in leads]))))

    pops = [(i, v) for i, v in enumerate(diff) if mid > 0 and v > a.pop * mid]
    print("\n== 内容突现(判据 C11:灰度 diff > %.1f× 运动中位数 = %.1f)==" % (a.pop, a.pop * mid))
    print("  %d 处(转场/首屏场景下,两个相邻爆点 = 内容两跳突现,冷启动闸不允许)" % len(pops))
    for i, v in pops[:10]:
        print("    t=%.3fs  diff=%.1f" % (ts[i], v))
    if len(pops) > 10:
        print("    ... 另有 %d 处" % (len(pops) - 10))

    if windows:
        print("\n== 运动窗口明细 ==")
        for s, e in windows[:10]:
            seg = dt[s:e + 1]
            print("  %.3f–%.3fs  %d 帧  dt p50 %.1f / max %.1f ms"
                  % (ts[s], ts[min(e + 1, len(ts) - 1)], e - s + 1,
                     float(np.median(seg)) * 1000 if len(seg) else 0,
                     float(seg.max()) * 1000 if len(seg) else 0))


if __name__ == "__main__":
    main()
