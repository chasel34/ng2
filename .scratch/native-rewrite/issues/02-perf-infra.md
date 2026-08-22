# 02 — 测量基建落盘(M0)

**What to build:** 让性能结论可复现,验收(票 19)前置依赖:
① 从 git 历史恢复 `docs/performance-diagnosis-2026-08-15.md`(`git show d2aaca4^:docs/performance-diagnosis-2026-08-15.md`,417 行,latch2present 判据唯一原始出处)。
② `scripts/perf/` 落盘两个分析脚本(各 ~60 行,按 research/perf-history.md §六 的方法重写):`analyze_framestats.py`(framestats 列按表头定位、IntendedVsync 间隔判丢帧、HandleInputStart→SwapBuffers 求 app 每帧 CPU)、`analyze_rec.py`(pyav 帧间 dt + 下采样灰度差分;「运动窗口内 dt 大洞」判停格、diff 爆点判内容突现)。
③ `docs/perf-playbook.md`:判据体系四层(录屏逐帧 > framestats > Perfetto > 探针)、latch2present 单峰/双峰判据、作废判据清单(模拟器 janky、GPU 直方图、High input latency、像素同色率)、实锤陷阱(HyperOS 变暗锁 60Hz、timestats 卡死、NGA 限流 ≥60s 冷却、release-only、input swipe ≤80ms 起步段无效、前台焦点确认)、「Android 触摸分发被父边界裁剪」模式备忘。每条注明原始出处。

**Blocked by:** None

**Status:** open

- [ ] 诊断文档恢复进 `docs/`,内容与 `d2aaca4^` 一致
- [ ] 两个脚本对一次真实采样(RN 版即可)跑通并输出判读
- [ ] playbook 成文,票 19 可直接引用其判据编号
