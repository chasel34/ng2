# 性能判据手册(perf-playbook)

本文维护当前 Android 工程的性能测量规则。历史票按编号引用，旧版本基线仅用于对照，不能当作当前版本已通过验收的证明。

正式包名为 `com.chasel.ng2`；开发包名为 `com.chasel.ng2.dev`。以下设备特性和阈值来自记录中的小米 120Hz 真机，换设备或刷新率需重新确认。
判据 = `C*`,作废判据 = `X*`,实锤陷阱 = `T*`,模式备忘 = `P*`。每条注明原始出处。

出处缩写:

| 缩写 | 文件 |
|---|---|
| 诊断 | `docs/performance-diagnosis-2026-08-15.md`（RN 历史记录，非当前缺陷清单） |
| 史 | `.scratch/native-rewrite/research/perf-history.md` |
| 转场 | `.scratch/perf-2026-08/transition-jank-2026-08-21.md` |
| 模拟器报告 | `.scratch/perf-2026-08/report.md`(2026-08-13,AVD) |
| 真机报告 | `.scratch/perf-2026-08/device-report.md`(2026-08-13/14,小米 25113PN0EC) |
| pager | `.scratch/pager-swipe/issues/01-commit-flash.md` |
| 触摸 | `.scratch/ui-polish-2026-08-20/issues/01-touch-input-dead.md` |

---

## 一、判据体系四层(按可信度降序)

低层判据与高层判据冲突时,**以层号小的为准**。

| 编号 | 层 | 内容 | 出处 |
|---|---|---|---|
| **C1** | 感知层 ground truth | 录屏逐帧:`screenrecord` → pyav 按 pts 算帧间 dt + 下采样灰度差分。工具 `scripts/perf/analyze_rec.py` | 史 §六;转场 §测量方法 1 |
| **C2** | 系统帧统计 | `dumpsys gfxinfo <pkg> framestats`:数 vsync 丢没丢。**分不出「动画窗口内」和「静止画面」,单看会误判**,必须配 C1。工具 `scripts/perf/analyze_framestats.py` | 史 §六;转场 §测量方法 2 |
| **C3** | 线程级取证 | Perfetto(sched + atrace view/gfx)。release 包靠 `<profileable android:shell="true"/>`;当前检查主线程、RenderThread 和相关工作线程；历史 RN 的 JS 线程结论仅用于追溯 | 转场 §测量方法 3;诊断 §第二轮「机制」 |
| **C4** | 临时探针 | 代码中使用单调时钟计时或 trace 标记，区分输入、加载、组合、布局和绘制成本,**验完即删** | 转场 §测量方法 4 |

## 二、量化判据

| 编号 | 判据 | 出处 |
|---|---|---|
| **C5** | **app 每帧 CPU** = framestats `HandleInputStart → SwapBuffers` 之和(输入+动画+测量布局+录制+同步+下发)。之后的交换缓冲不归 app。用于分析 app 侧 CPU 阶段，需配合 GPU、FrameTimeline 和录屏判断；不能独自代表完整送显延迟。历史基线见历史参考 | 模拟器报告 §2.4;真机报告 §2 |
| **C6** | **丢帧(framestats 口径)** = 相邻帧 `IntendedVsync` 间隔 > 13ms(120Hz)。换刷新率时阈值取 ≈1.5× 帧间隔。**只在运动段内算**:≥100ms 的空档是 app 没内容要画(静止/换阶段),单独列出不计丢帧,否则一段静止就能刷出几千「丢帧」(票 52) | 史 §六;转场 §测量方法 2 |
| **C7** | **丢帧(Perfetto 口径)** = `actual_frame_timeline_slice` 里 `present_type='Dropped Frame'` 的行数——**不是 name 列**。判 drop 成不成簇:成簇(如 8 个落在 170ms 内)=肉眼可见停格+双倍跳;孤立单帧 drop 属平台余量 | 诊断 §第五轮;史 §六 |
| **C8** | **latch2present 单峰/双峰**。timestats 正常时沿用原口径；若已触发 T2，改采 `android.surfaceflinger.frametimeline`，把 app actual surface frame 按 `display_frame_token` 配到 actual display frame，以 display slice `dur`（SF actual frame start→present）作等价峰形。Android 16 FrameTimeline 不暴露 `lastLatchTime`，故不得把 app surface `dur`（只到 buffer ready/acquire fence）冒充 latch2present。@120Hz **低延迟单峰**=9–11ms；**双峰**=9–11ms 与相隔一档 vsync 的 17–20ms 两簇，表示队列深度在 1↔2 间振荡。工具 `analyze_frametimeline.py`：次峰≥5% 即双峰；高延迟单峰也不能按低峰通过。历史采样见历史参考 | 诊断 §第二轮 + §第二轮复测；票 56 |
| **C9** | **present2present** = 送显间隔。满帧送显的旁证(8ms @120Hz),但均匀送显间隔不能排除双峰，单看可能漏判 → 只能配 C8 使用 | 诊断 §第二轮 |
| **C10** | **停格(录屏口径)** = **运动窗口内**的 dt 大洞。**静止画面的出帧空洞不算缺陷**,且窗口的**第一个 dt 也不算**——它跨的是「最后一帧静止画面 → 动画首帧」,即点击到起步的延迟,期间屏幕一动不动。真机点一下会把屏幕顶到 120Hz 保持约 600ms(touch boost),boost 退了就停帧,于是每个「点击 → 动画」前都有 60–210ms 空洞,三个包都有;它算不算进窗口只差一帧(见 X6) | 史 §六;转场 §测量方法 1;票 53 |
| **C11** | **内容突现(录屏口径)** = 灰度 diff 爆点。冷启动闸要求:无白/黑闪、无内容两跳突现(两个相邻爆点即两跳) | 转场 §测量方法 1;spec §五 场景 1 |
| **C12** | **janky%(现代 FrameTimeline 口径)在真机上可信**(模拟器上作废,见 X1)。不同构建、设备与场景的历史基线不能作为当前通过阈值 | 真机报告 §2、§4 |
| **C13** | **逐帧位移(相位相关)**:拖拽段每帧像素位移应恒定(实测 104px/帧)。异常有两型——「单帧 0px 停格 → 数帧后双倍补跳」(体感=向上跳一下)、「48/56px 半步顿挫」(体感=顿一下) | 诊断 §第四轮 |

## 三、作废判据(测到了也不算数)

| 编号 | 作废项 | 为什么 | 出处 |
|---|---|---|---|
| **X1** | **模拟器的 `Janky frames`** | 被 guest→host GL 管道固定 13.4ms 交换缓冲淹没:同一份数据里两个屏 janky 差 180 倍(0.3% vs 54.1%),而帧时长 p50 18.3 vs 18.4ms 统计上无法区分,总时长 18ms 又远小于 33.3ms 截止时间——它量的不是 app 渲染成本 | 模拟器报告 §2.1–2.3 |
| **X2** | **`gfxinfo` 的 GPU 直方图** | 队列满时含 swap 排队时间,把 3–4ms 的真实 GPU 成本虚标成 14ms+。要真值看 Perfetto 里 RenderThread 的 `queueBuffer`/`waitForever` 切片 | 诊断 §第二轮「测量陷阱」 |
| **X3** | **`Number High input latency`** | `adb shell input` 注入的事件时间戳天然是旧的,恒为满值,零信息量 | 模拟器报告 §2;真机报告 §2 |
| **X4** | **自制「像素同色率」空白检测器** | 2026-08-13 那轮自制的空白帧检测,已作废,不再使用 | 真机报告 §2 |
| **X5** | **legacy janky 与现代 janky 混用** | 同一次采样两个口径能给出相反结论(现代 0.07% vs legacy 46.89%)。裁决只认现代 FrameTimeline 口径(C12);legacy 只在「是否贴着 60Hz 节奏」这一个问题上做参考 | 诊断 §真机复测结果、§第二轮复测 |
| **X6** | **运动窗口第一个 dt 当停格** | 它是「点击 → 动画起步」的延迟,屏幕在这段里一动不动,按 C10 本就不该算。窗口从哪一格起算只差一帧:touch boost 退出后恢复的第一帧若恰好已是动画首帧,空洞就被并进窗口;若动画晚一个 vsync 起步,空洞就落在窗口外。票 53 判「原生 21 处 / anzong 0 处」的全部差距来自这一帧——两边动画自身的 max dt 是 17.7ms 与 13.8ms,无可见差,且原生的起步延迟(中位 119ms)反而比 anzong(中位 181ms)短 | 票 53 |

## 四、实锤陷阱(先排除,再下结论)

| 编号 | 陷阱 | 出处 |
|---|---|---|
| **T1** | **屏幕闲置变暗后 HyperOS 把刷新率锁到 60Hz**,app 窗口投票失效,latch2present 全落 17–18ms,看起来像队列回退。判定前必须**在滚动中**确认 `dumpsys display` 里有 `frameRateOverride {uid=<app> 120}` / `renderFrameRate=120` | 诊断 §第二轮「测量陷阱」 |
| **T2** | **出现 `SurfaceFlinger --timestats` 持续无 layer 输出的故障时**，停止反复 enable/clear，记录设备与故障时间；C8 改用单轮 Perfetto：`adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/s9.pb < scripts/perf/frametimeline.cfg`，15 秒内操作，pull 后跑 `analyze_frametimeline.py`。采样前后按 T4 验前台、运动中按 T1 验 120Hz；trace 首尾越过目标包则整轮作废。framestats 只保留为连续丢帧旁证，不能替代峰形 | 诊断 §第三轮「边界与陷阱」；票 56 |
| **T3** | **framestats 的列必须按表头名定位**:新版在 `Flags` 后插了 `FrameTimelineVsyncId`,按固定下标取列会整体错位一格 | 史 §六;模拟器报告 §2.3 |
| **T4** | **测量前确认前台焦点是被测 app**(`dumpsys window` 的 `mCurrentFocus`)。通知栏/锁屏盖住时 gfxinfo 读数是垃圾 | 诊断 §第三轮「边界与陷阱」;转场 §复现/验收脚本 |
| **T5** | **`adb shell input swipe` ≤80ms 的起步段不作证据**:注入时序 artifact,两家 app 都有。注入拖拽整体也与真手指不同(事件点更稀疏、t=100–250ms 区间每隔一帧丢 vsync),不能当缺陷 | 诊断 §第四轮;转场 §遗留 3;模拟器报告 §1「已知的口径局限」 |
| **T6** | **启动可能联网的场景应避免连续请求 NGA**：冷启动循环之间 ≥60s 冷却，等待放在测量区间外（历史限流实测静置 75s 恢复）。当前 `StartupBenchmark` 的首页可能联网，已在 `setupBlock` 中冷却；五轮至少增加五分钟等待。成片网络失败先怀疑是自己打出来的,别记成服务端拦截 | 史 §七;真机报告 §8「方法论」;转场 §复现/验收脚本 |
| **T7** | **只用本地 `assembleRelease` 裁决**，debug 包数据不用于裁决。`adb install -r` 仅在同包名、同签名时覆盖并保留数据；release 与 debug 包名不同、数据独立，不能互相覆盖保登录态 | 诊断 §第二轮;真机报告 §5.1、§5.2 |
| **T8** | **模拟器永不裁性能**:面板报 120 但 `mActiveRenderFrameRate=60`,验不了 120Hz;叠加 X1,AVD 上的 A/B 全是噪声(2026-08-11/12 一整轮打空) | 史 §五.1、§六;spec §五 |
| **T9** | **帧级 A/B 的运行间噪声 ~±5ms**(列表内容、字形缓存、调度都在变)。小于一档 vsync 的差异不是结论 | 诊断 §第三轮「边界与陷阱」 |
| **T10** | **Perfetto 受 SELinux 限制**:配置走 stdin(`cat cfg \| perfetto -c -`),输出必须落 `/data/misc/perfetto-traces/`。构建 OOM 应检查当前 daemon 堆配置和失败日志，历史 2G 堆问题不代表当前构建状态 | 诊断 §第五轮「坑位记录」 |
| **T11** | **release 保留 `<profileable android:shell="true"/>`**，用于 C3 的 app 线程取证；不能因 debug 包可调试就认为 release 也能采到相同信息 | 诊断 §第二轮「测量陷阱」;史 §六「基建缺口」 |
| **T12** | **同一次采样必须确认测的是哪个变体**:release 与 dev 变体并装时容易测错对象(当前 release 为 `com.chasel.ng2`、debug 为 `com.chasel.ng2.dev`；`com.chasel.ng2.n` 仅是早期重写阶段包名) | 史 §六;spec §三 |
| **T13** | **历史重写验收未将网络首屏耗时列入渲染闸**：当时冷启首屏 2.4–2.8s 主要来自 NGA RTT 与轮换。当前排查仍应拆开网络、存储与渲染耗时，不能用此条排除新的启动回归 | 史 §七;真机报告 §4;spec §一.4 |
| **T14** | **framestats 的 `Flags` 不能按「非 0 即无效」过滤**:hwui 只有低 4 位语义稳定(`WindowLayoutChanged=1`/`RTAnimation=2`/`SurfaceCanvas=4`/`SkippedFrame=8`),bit4 以上是新版追加的常态位。Android 16(API 36)真机上 bit5(=32)几乎覆盖每一个交互/滚动帧,老口径会把整份采样清空。只按 `Flags & 13` 剔除,再用时间戳单调性兜底跳过帧 | 票 52;`scripts/perf/README.md` |

## 五、历史参考

旧 RN 基线、设备故障记录与 P1 触摸模式见 [历史参考](perf-history-reference.md)，仅在追溯对应问题时读取。

## 六、工具

`scripts/perf/`(用法与再生方式见 `scripts/perf/README.md`):

- `capture.py` — 统一采集 framestats 或 FrameTimeline，保存采样前、中、后的设备证据与 `metadata.json`；未知项不推定为通过。
- `analyze_framestats.py` — C2 / C5 / C6 层,纯标准库。
- `analyze_rec.py` — C1 / C10 / C11 层,依赖 pyav + numpy(venv)。
- `analyze_frametimeline.py` — C8 单峰/双峰,依赖 perfetto(venv)；采样配置
  `frametimeline.cfg`，不调用 timestats enable/clear。

上述三个通用分析脚本接受 `--source`（默认 `unknown`），它是调用者声明，非 `device` 时在输出首段打「本次数据不可用于性能裁决」。

其他专项工具（滚动速度、GPU 等待、S9 分段）见 [脚本说明](../scripts/perf/README.md)；它们没有 `--source` 校验，使用者须记录设备、包名、构建和场景。
