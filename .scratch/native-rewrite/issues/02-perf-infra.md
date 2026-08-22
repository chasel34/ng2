# 02 — 测量基建落盘(M0)

**What to build:** 让性能结论可复现,验收(票 19)前置依赖:
① 从 git 历史恢复 `docs/performance-diagnosis-2026-08-15.md`(`git show d2aaca4^:docs/performance-diagnosis-2026-08-15.md`,417 行,latch2present 判据唯一原始出处)。
② `scripts/perf/` 落盘两个分析脚本(各 ~60 行,按 research/perf-history.md §六 的方法重写):`analyze_framestats.py`(framestats 列按表头定位、IntendedVsync 间隔判丢帧、HandleInputStart→SwapBuffers 求 app 每帧 CPU)、`analyze_rec.py`(pyav 帧间 dt + 下采样灰度差分;「运动窗口内 dt 大洞」判停格、diff 爆点判内容突现)。
③ `docs/perf-playbook.md`:判据体系四层(录屏逐帧 > framestats > Perfetto > 探针)、latch2present 单峰/双峰判据、作废判据清单(模拟器 janky、GPU 直方图、High input latency、像素同色率)、实锤陷阱(HyperOS 变暗锁 60Hz、timestats 卡死、NGA 限流 ≥60s 冷却、release-only、input swipe ≤80ms 起步段无效、前台焦点确认)、「Android 触摸分发被父边界裁剪」模式备忘。每条注明原始出处。

**Blocked by:** None

**Status:** in-review

- [x] 诊断文档恢复进 `docs/`,内容与 `d2aaca4^` 一致
- [x] 两个脚本对一次真实采样(RN 版即可)跑通并输出判读
- [x] playbook 成文,票 19 可直接引用其判据编号

## Comments

### 完成摘要

- `docs/performance-diagnosis-2026-08-15.md` 恢复,417 行,与 `d2aaca4^` 逐字节一致
  (`shasum` 双边同为 `9a15c4c505b0572502a180ff6c0f5577873ca5a6`),未改一字。
- `scripts/perf/analyze_framestats.py`(141 行,纯标准库)、`scripts/perf/analyze_rec.py`(124 行,pyav+numpy)、
  `scripts/perf/README.md`(再生方式与用法)。`scripts/perf/.venv/` 进 `.gitignore`,
  重建两行:`python3 -m venv scripts/perf/.venv` + `scripts/perf/.venv/bin/pip install av numpy`(实测装到 av 18.1.0 / numpy 2.5.2)。
- `docs/perf-playbook.md`(98 行):C1–C13 判据、X1–X5 作废判据、T1–T13 陷阱、P1 模式备忘,每条注明出处文件与节号。

### 判据编号(票 19 引用口径)

- **C1–C4** = 判据体系四层(录屏逐帧 > framestats > Perfetto > 探针);
- **C5** app 每帧 CPU、**C6** framestats 丢帧、**C7** `present_type='Dropped Frame'`、
  **C8** latch2present 单峰/双峰、**C9** present2present、**C10** 停格、**C11** 内容突现、
  **C12** 现代 janky%、**C13** 逐帧位移;
- **X1** 模拟器 janky、**X2** GPU 直方图、**X3** High input latency、**X4** 像素同色率、**X5** legacy/现代口径混用;
- **T1** 变暗锁 60Hz … **T13** 首屏耗时不进验收;
- **P1** Android 触摸分发被父边界裁剪。

两个脚本的输出与 `--help` 里都带编号,改编号时脚本要一起改(`grep -n 'C[0-9]\|T[0-9]\|X[0-9]' scripts/perf/*.py`)。

### 真实采样跑通(模拟器,数据不可用于裁决)

`emulator-5554` / `com.chasel.ng2`(RN release)/ 网事杂谈主题列表 / 7 次 300ms `input swipe`(>80ms,避开 T5):

- `analyze_framestats.py`:PROFILEDATA block 1 个、59 帧、跨度 6920ms;自动判出**帧间隔基准 16.67ms ≈ 60Hz**
  并打警告「本机不在 120Hz,13ms 阈值会把每一帧都判成丢帧,换机口径用 --drop-ms ≈ 25.0ms」;
  app 每帧 CPU p50 **146.8ms**、逐阶段里「下发绘制指令」54ms +「交换缓冲」76ms 吃掉几乎全部时间。
  **判读:这正是 X1 描述的形态**——AVD 的 GL 管道把一切淹没,app 侧成本无从分辨(真机同一指标基线是 2.2–2.5ms)。
  只证明列按表头定位(本机表头确有 `FrameTimelineVsyncId`)、阶段拆分、丢帧计数、百分位都对得上原始行手算值。
- `analyze_rec.py`:8s `screenrecord` 拉回本地 → 30 帧 / 6.21s、**基准帧间隔 167ms ≈ 6fps**;
  识别出 3 段运动窗口、运动窗口内停格 2 处(711ms / 567ms)、静止画面出帧空洞 1 处**已按 C10 排除不计**、内容突现 0 处。
  **判读:AVD 上 screenrecord 只有 6fps,连感知层采样都不成立**,同样只证明 pyav 解码、pts→dt、
  灰度差分、运动窗口切分、静止空洞排除这条链路可用。
- 两次输出的首段都打了「来源=emulator → 本次数据【不可用于性能裁决】」。

### 关键决定

- **判据前缀分三族**:C=判据、X=作废判据、T=陷阱,另加 P=模式备忘。票里只举例了 `C1/T1/X1`,
  实际把「录屏停格/内容突现」编成 C10/C11(它们是判据不是陷阱),T 留给陷阱。
- `--drop-ms` 默认 13ms(perf-history §六 的 120Hz 判据),但脚本会从 `FrameInterval` 列自测刷新率,
  阈值低于基准间隔时打警告并给出 1.5× 建议值——否则在 60Hz 机器上会得到「丢帧率 608%」这种无意义数字。
- framestats 里 `Flags != 0` 的行(首帧/窗口变更帧)默认剔除并单独报数。
- 两个脚本都强制 `--source`,默认 `unknown` 也按「不可裁决」处理:宁可多打一行提示,不让模拟器数据混进结论。
- latch2present(C8)与 Perfetto 的 `present_type`(C7)**没有落脚本**:前者要 `SurfaceFlinger --timestats`
  且有 T2 的卡死风险,后者要 trace_processor 查 `actual_frame_timeline_slice`,两者都只在真机上有意义,
  留到票 19 按需补(已写进 `scripts/perf/README.md`「还没落盘的」)。

### 未完成 / 待所有者

- 无阻塞项。**票 19 的真机采样必须由所有者提供小米 17(120Hz)真机**;本票只在模拟器上验证脚本可跑,
  按 T8/X1 不产出任何性能结论。

### 发现的票外问题

1. **本 worktree 建在 `eb0d80b`(落后 `android-native` 11 个提交,没有 `.scratch/native-rewrite/`)**。
   工作树干净,已 `git merge --ff-only 38a6311` 快进到与主检出同基(不是 rebase、没切分支、没 push)。
   其它子代理的 worktree 若也建在旧点,开工前需同样快进。
2. `plugins/with-profileable.js` 与审计 P3-01 冲突已记在 playbook T11(原生版保留 profileable)。
   spec §一.5 的「已知缺陷不随迁」清单里没有这条,主控确认一下要不要补进 spec。
3. RN 版那个「整窗口触摸完全失灵」(`.scratch/ui-polish-2026-08-20/issues/01-touch-input-dead.md`)
   仍 open 且无定位,playbook P1 只记了模式对照,按 perf-history §四.3 不进原生验收。
