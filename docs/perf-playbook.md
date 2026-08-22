# 性能判据手册(perf-playbook)

本文是 ng2 / ng2n 性能裁决的**唯一判据出处**。票 19(真机性能验收)按本文编号引用。
判据 = `C*`,作废判据 = `X*`,实锤陷阱 = `T*`,模式备忘 = `P*`。每条注明原始出处。

出处缩写:

| 缩写 | 文件 |
|---|---|
| 诊断 | `docs/performance-diagnosis-2026-08-15.md`(2026-08-15 五轮真机诊断,417 行) |
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
| **C3** | 线程级取证 | Perfetto(sched + atrace view/gfx)。release 包靠 `<profileable android:shell="true"/>`;release 的 JS 线程无 systrace 段,用 `thread_state` 的 Running 区间推断 | 转场 §测量方法 3;诊断 §第二轮「机制」 |
| **C4** | 临时探针 | 代码里插 `performance.now()` / 计时日志,拆 press→render→commit→mount 的账,**验完即删** | 转场 §测量方法 4 |

## 二、量化判据

| 编号 | 判据 | 出处 |
|---|---|---|
| **C5** | **app 每帧 CPU** = framestats `HandleInputStart → SwapBuffers` 之和(输入+动画+测量布局+录制+同步+下发)。之后的交换缓冲不归 app。这是对 app 侧改动**唯一敏感**的量,也是真机上唯一挤占 8.33ms 预算的部分。真机基线:版块列表 2.52ms、帖子详情 2.16ms | 模拟器报告 §2.4;真机报告 §2 |
| **C6** | **丢帧(framestats 口径)** = 相邻帧 `IntendedVsync` 间隔 > 13ms(120Hz)。换刷新率时阈值取 ≈1.5× 帧间隔 | 史 §六;转场 §测量方法 2 |
| **C7** | **丢帧(Perfetto 口径)** = `actual_frame_timeline_slice` 里 `present_type='Dropped Frame'` 的行数——**不是 name 列**。判 drop 成不成簇:成簇(如 8 个落在 170ms 内)=肉眼可见停格+双倍跳;孤立单帧 drop 属平台余量 | 诊断 §第五轮;史 §六 |
| **C8** | **latch2present 单峰/双峰**(`SurfaceFlinger --timestats`)。**单峰**(9–10ms @120Hz)= 缓冲队列深度恒定 = 无感;**双峰**(9–10ms 与 17–18ms 两簇)= 深度在 1↔2 之间振荡 = 「有帧率没手感」的微顿,内容时间轴每跳一次错位 8.3ms,且多背 1 帧触摸延迟。实测:anzong 单峰 411/415;ng2 修复前双峰 342/194;ng2 修复后单峰 524/527 | 诊断 §第二轮 + §第二轮复测 |
| **C9** | **present2present** = 送显间隔。满帧送显的旁证(8ms @120Hz),但**双峰问题上它两边都好看**(411/415 vs 443/447),单看必漏判 → 只能配 C8 使用 | 诊断 §第二轮 |
| **C10** | **停格(录屏口径)** = **运动窗口内**的 dt 大洞。**静止画面的出帧空洞不算缺陷** | 史 §六;转场 §测量方法 1 |
| **C11** | **内容突现(录屏口径)** = 灰度 diff 爆点。冷启动闸要求:无白/黑闪、无内容两跳突现(两个相邻爆点即两跳) | 转场 §测量方法 1;spec §五 场景 1 |
| **C12** | **janky%(现代 FrameTimeline 口径)在真机上可信**(模拟器上作废,见 X1)。RN 版真机基线:慢拖 0.1%、快甩 2.7%;分场景 p50 9.3–9.5ms、p95 10.1–11.0ms、无 >33ms 帧 | 真机报告 §2、§4 |
| **C13** | **逐帧位移(相位相关)**:拖拽段每帧像素位移应恒定(实测 104px/帧)。异常有两型——「单帧 0px 停格 → 数帧后双倍补跳」(体感=向上跳一下)、「48/56px 半步顿挫」(体感=顿一下) | 诊断 §第四轮 |

## 三、作废判据(测到了也不算数)

| 编号 | 作废项 | 为什么 | 出处 |
|---|---|---|---|
| **X1** | **模拟器的 `Janky frames`** | 被 guest→host GL 管道固定 13.4ms 交换缓冲淹没:同一份数据里两个屏 janky 差 180 倍(0.3% vs 54.1%),而帧时长 p50 18.3 vs 18.4ms 统计上无法区分,总时长 18ms 又远小于 33.3ms 截止时间——它量的不是 app 渲染成本 | 模拟器报告 §2.1–2.3 |
| **X2** | **`gfxinfo` 的 GPU 直方图** | 队列满时含 swap 排队时间,把 3–4ms 的真实 GPU 成本虚标成 14ms+。要真值看 Perfetto 里 RenderThread 的 `queueBuffer`/`waitForever` 切片 | 诊断 §第二轮「测量陷阱」 |
| **X3** | **`Number High input latency`** | `adb shell input` 注入的事件时间戳天然是旧的,恒为满值,零信息量 | 模拟器报告 §2;真机报告 §2 |
| **X4** | **自制「像素同色率」空白检测器** | 2026-08-13 那轮自制的空白帧检测,已作废,不再使用 | 真机报告 §2 |
| **X5** | **legacy janky 与现代 janky 混用** | 同一次采样两个口径能给出相反结论(现代 0.07% vs legacy 46.89%)。裁决只认现代 FrameTimeline 口径(C12);legacy 只在「是否贴着 60Hz 节奏」这一个问题上做参考 | 诊断 §真机复测结果、§第二轮复测 |

## 四、实锤陷阱(先排除,再下结论)

| 编号 | 陷阱 | 出处 |
|---|---|---|
| **T1** | **屏幕闲置变暗后 HyperOS 把刷新率锁到 60Hz**,app 窗口投票失效,latch2present 全落 17–18ms,看起来像队列回退。判定前必须**在滚动中**确认 `dumpsys display` 里有 `frameRateOverride {uid=<app> 120}` / `renderFrameRate=120` | 诊断 §第二轮「测量陷阱」 |
| **T2** | **`SurfaceFlinger --timestats` 反复 enable/clear 若干轮后会卡死**(dump 出 0 层),disable/enable 也救不回。退回 framestats 的 IntendedVsync 间隔 + FrameCompleted 总耗时 | 诊断 §第三轮「边界与陷阱」 |
| **T3** | **framestats 的列必须按表头名定位**:新版在 `Flags` 后插了 `FrameTimelineVsyncId`,按固定下标取列会整体错位一格 | 史 §六;模拟器报告 §2.3 |
| **T4** | **测量前确认前台焦点是被测 app**(`dumpsys window` 的 `mCurrentFocus`)。通知栏/锁屏盖住时 gfxinfo 读数是垃圾 | 诊断 §第三轮「边界与陷阱」;转场 §复现/验收脚本 |
| **T5** | **`adb shell input swipe` ≤80ms 的起步段不作证据**:注入时序 artifact,两家 app 都有。注入拖拽整体也与真手指不同(事件点更稀疏、t=100–250ms 区间每隔一帧丢 vsync),不能当缺陷 | 诊断 §第四轮;转场 §遗留 3;模拟器报告 §1「已知的口径局限」 |
| **T6** | **NGA 会因连续冷启动限流**:冷启动循环之间 ≥60s 冷却(实测静置 75s 恢复)。成片网络失败先怀疑是自己打出来的,别记成服务端拦截 | 史 §七;真机报告 §8「方法论」;转场 §复现/验收脚本 |
| **T7** | **只用本地 `assembleRelease` 裁决**,与 debug 同 keystore、`adb install -r` 覆盖保登录态;debug 包数据一律作废。注意反向结论也成立:debug 与 release 的 latch2present **双峰形态一致**,所以双峰这条不是 JS dev 模式引起的 | 诊断 §第二轮;真机报告 §5.1、§5.2 |
| **T8** | **模拟器永不裁性能**:面板报 120 但 `mActiveRenderFrameRate=60`,验不了 120Hz;叠加 X1,AVD 上的 A/B 全是噪声(2026-08-11/12 一整轮打空) | 史 §五.1、§六;spec §五 |
| **T9** | **帧级 A/B 的运行间噪声 ~±5ms**(列表内容、字形缓存、调度都在变)。小于一档 vsync 的差异不是结论 | 诊断 §第三轮「边界与陷阱」 |
| **T10** | **Perfetto 受 SELinux 限制**:配置走 stdin(`cat cfg \| perfetto -c -`),输出必须落 `/data/misc/perfetto-traces/`。另:gradle daemon 2G 堆在 `:app:packageRelease` 会 OOM | 诊断 §第五轮「坑位记录」 |
| **T11** | **release 可测量性靠 `<profileable android:shell="true"/>`**(RN 版做成常驻的 `plugins/with-profileable.js`)。没有它,C3 在 release 包上抓不到 app 线程。安全审计曾建议把它限制到 dev/preview——与性能纪律冲突,**原生版保留 profileable** | 诊断 §第二轮「测量陷阱」;史 §六「基建缺口」 |
| **T12** | **同一次采样必须确认测的是哪个变体**:release 与 dev 变体并装时容易测错对象(RN 版 `com.chasel.ng2` / `com.chasel.ng2.dev`,原生版 `com.chasel.ng2.n` 又是第三个) | 史 §六;spec §三 |
| **T13** | **首屏耗时不是本次验收对象**:冷启首屏 2.4–2.8s 是 NGA RTT + 反封锁链轮换,同期 app 每帧 CPU 只有 2.2–2.5ms,与渲染无关 | 史 §七;真机报告 §4;spec §一.4 |

## 五、模式备忘

**P1 — Android 触摸分发被父边界裁剪**(出处:pager「过程中踩的新坑」;触摸「排查线索」)

症状指纹:**点击/手势识别一切正常,唯独容器里的原生纵向滚动一个 move 都收不到、无过滚辉光**。
机制:RN(Fabric)Android 有两套命中测试——JS 侧的 `TouchTargetHelper`(Pressable、RNGH 走它,支持 overflow/transform),
和 Android 原生 `dispatchTouchEvent`(ScrollView 这类原生手势组件走它,**按子 view 的布局边界裁剪**,transform 会被逆变换回去再比对)。
子 view 布局在父边界外、靠 transform 拉回屏内时,前者通过、后者裁掉。

2026-08-19 在 SwipePager 重构里踩实:轨道 `absoluteFill`(一屏宽),面板 `left=(页号-1)*屏宽` 落在边界外、transform 平移回屏 → taps 全通、滚动全灭。
修法:轨道显式 `width = count * 屏宽`,让所有面板落在布局边界内。

原生(Compose)一侧不共用这套双命中测试,但**同型问题存在**:自定义 `layout`/`graphicsLayer` 平移把子节点放到父节点边界外时,
命中测试同样按未平移的布局边界裁剪。写 pager/轮播/抽屉类组件时,**子面板必须落在容器布局边界内**。

另有一条**未定位**的相关缺陷(不进原生验收,只作对照):RN 版偶发「整窗口触摸完全失灵,画面正常,force-stop 才恢复」——
与 P1 的区别是**点击也一起死**,不完全吻合;无确定复现,`logcat` 无异常无 ANR(出处:触摸)。

## 六、工具

`scripts/perf/`(用法与再生方式见 `scripts/perf/README.md`):

- `analyze_framestats.py` — C2 / C5 / C6 层,纯标准库。
- `analyze_rec.py` — C1 / C10 / C11 层,依赖 pyav + numpy(venv)。

两个脚本都要求 `--source`,非 `device` 时在输出首段打「本次数据不可用于性能裁决」。
