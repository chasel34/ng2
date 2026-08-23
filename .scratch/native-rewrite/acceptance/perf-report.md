# 票 19 真机性能验收报告

**状态:** 进行中  
**已复验缺陷:** 票 51 Baseline Profile 采集为空(修复基线 `34589c6`)

## 环境

| 项 | 值 |
|---|---|
| 验收日期 | 2026-08-23 |
| Git 基线 | `34589c6`(`android-native`) |
| 设备 | 小米 `25113PN0EC`,device `pudding` |
| 系统 | Android 16 / SDK 36 |
| 屏幕 | 1220×2656 @520dpi,目标 120Hz |
| ADB 目标 | `192.168.0.101:40039` |
| 原生包 | `com.chasel.ng2.n` |
| Baseline Profile 源文件 | `baseline-prof.txt`,23,899 行, SHA-256 `75ce22259585423e06e38808105ba46f2addc3dc22fbb5b0ccd1740ce548abdd` |
| Release APK | `app-release.apk`,5.0 MiB, SHA-256 `c803ccfb2d7d175f98a8656c670c787cbab2cd14db15125ec841219827656c43` |
| 包变体 | release,非可调试,`com.chasel.ng2.n` 0.1.0(1) |
| Profile 打包 | APK 含 `assets/dexopt/baseline.prof`(10,400 B)与 `baseline.profm`(1,396 B) |
| 设备 dexopt | `arm64: [status=speed-profile] [reason=baseline]`,odex 7,868 KiB |

## Baseline Profile 闸前检查

票 51 修复后,profileBlock 改为首页→版块→详情→甩动并驻留 ≥12 秒;生成文件已在
`34589c6` 提交。设备 `dumpsys package com.chasel.ng2.n` 明确报告:

```text
Dexopt state:
  [com.chasel.ng2.n]
    arm64: [status=speed-profile] [reason=baseline] [primary-abi]
```

APK profile 资产、release 非可调试属性与真机 dexopt 三项均通过。下一步进行
`compile --reset` 与 `speed-profile` 冷启 A/B;A/B 后恢复 `speed-profile` 再测十场景。

### 冷启 A/B(进行中)

口径为 `am force-stop` 后 `am start -W`,相邻冷启 ≥60 秒。先做一次不计样本的首装预热
(244 ms),排除首次安装数据初始化。`compile --reset` 后 dexopt 为
`verify / reason=install`,有效样本为 220 / 155 / 149 ms(中位数 155 ms)。

覆盖安装同一 release APK 后恢复 `speed-profile / reason=baseline`,再显式执行
`compile -f -m speed-profile` 后为 `speed-profile / reason=cmdline`,odex 7,868 KiB。
第一轮 profile 组 167 / 145 / 274 ms 因末次发现锁屏且前两次缺少逐样本 T4 焦点证明而
整组作废。解锁后重采有效 profile 组为 167 / 164 / 150 ms(中位数 164 ms),每次前后
`mCurrentFocus` 均为 `com.chasel.ng2.n/com.chasel.ng2n.MainActivity`。

| 编译状态 | 样本(ms) | 中位数 | 与 reset 中位数 |
|---|---:|---:|---:|
| `verify / reason=install` | 220 / 155 / 149 | 155 ms | — |
| `speed-profile / reason=cmdline` | 167 / 164 / 150 | 164 ms | +9 ms |

差值约一档 120Hz vsync且样本少,不超过帧级 A/B 的裁决能力(T9);首屏耗时又不属于本次
验收对象(T13),故只记录、不据此判缺陷。profile 打包与设备 dexopt 生效本身已实证通过。

## 当前进度

- 已完成:release/profile 三重核验与冷启 A/B。
- 十场景:6/10 完成;场景 3、4、5 通过,场景 6、7、8 不通过。
- 登录态:已由所有者完成;验收不卸载、不清数据。

## 十场景

| # | 场景 | 结果 | 说明 |
|---|---|---|---|
| 1 | 冷启动闪烁 | 待测 | — |
| 2 | 冷启后首次进主题 | 待测 | — |
| 3 | 主题列表快甩 | **通过** | 原生现代 janky 0.02%(1/6,318),≤1% |
| 4 | 楼层流慢拖/快甩 | **通过** | 原生 0.00% / 0.43%,不差于 RN 基线 0.1% / 2.7% |
| 5 | 横滑翻页 | **通过** | 原生 14/14 运动窗口松手等价丢帧≤1,不差于 RN |
| 6 | 抽屉开合 | **不通过** | 原生 C1 运动窗口停格 21 处,anzong 0 处;票 53 |
| 7 | 附件展开/收起 | **不通过** | 宫格瞬时替换且现代 janky 20.16%;票 54 |
| 8 | 大图/画廊开合与缩放 | **不通过** | 缩放平移可将图片整体移出视口并持续黑屏;票 55 |
| 9 | 各转场 latch2present / 连续丢帧 | 待测 | — |
| 10 | 动画交互伞条款 | 待测 | — |

## 结论

进行中;待十场景与伞条款全部完成后裁决。

## 场景 3 — 主题列表快甩

**脚本:** 三个包均进入“网事杂谈”主题列表,执行 25 次固定坐标
`input swipe 610 2100 610 550 300`,每次间隔 900 ms,总计约 30 秒。采样前 reset
gfxinfo,采样后确认前台焦点。T5 的单次注入起步 ≤80 ms 不单独作为证据。

| 包 | 120Hz 检查 | 总帧 | 现代 janky | p50 / p95 / p99 | 裁决用途 |
|---|---|---:|---:|---:|---|
| 原生 `com.chasel.ng2.n` | `frameRateOverride uid=10375 120.00001`,render 120 | 6,318 | **1(0.02%)** | 5 / 9 / 17 ms | 主裁决 |
| RN `com.chasel.ng2` | `frameRateOverride uid=10368 120.00001` | 8,480 | 0(0.00%) | 17 / 18 / 18 ms | 对拍 |
| anzong | 不可用 | — | — | — | 主题列表连续两次“加载失败,请重试”,未把失败页数据冒充对拍 |

原生现代 janky 0.02% ≤1%,**场景 3 通过**。RN legacy janky 53.09% 与现代 0.00%
冲突,按 X5 作废;GPU 直方图按 X2 不使用。C5/C6 脚本因 API 36 `Flags=32`
全过滤,已开票 52;原始 framestats 全部保留。anzong 首次进入及冷却后重试均停在
“加载失败,请重试”,其失败页 50 帧数据已作废,不作性能比较(T6)。

证据:`perf/s3-native-framestats.txt`、`perf/s3-rn-framestats.txt`、
`perf/s3-anzong-framestats.txt`。

## 场景 4 — 楼层流慢拖 / 快甩

进入有多楼层的主题详情。慢拖脚本为 20 次
`input swipe 610 1800 610 900 1200`,间隔 300 ms;快甩脚本与场景 3 相同。
每段约 30 秒,独立 reset gfxinfo,采样前确认原生/RN 滚动中 120Hz override,采样后
确认前台焦点。

| 包 | 慢拖现代 janky | 慢拖 p50/p95/p99 | 快甩现代 janky | 快甩 p50/p95/p99 |
|---|---:|---:|---:|---:|
| 原生 | **0/7,174(0.00%)** | 5/17/19 ms | **18/4,202(0.43%)** | 10/15/17 ms |
| RN 本轮 | 0/6,896(0.00%) | 8/9/11 ms | 0/3,282(0.00%) | 9/11/20 ms |
| RN 历史基线(C12) | **0.1%** | — | **2.7%** | — |
| anzong | 不可用 | 主题列表持续加载失败,无法进入楼层流 | 不可用 | 同左 |

闸要求是不差于 RN 历史基线。原生慢拖 0.00% ≤0.1%,快甩 0.43% ≤2.7%,
故**场景 4 通过**。legacy 与现代冲突时按 X5 作废;`Number High input latency`
按 X3 作废。

证据:`perf/s4-native-slow.txt`、`perf/s4-native-fast.txt`、
`perf/s4-rn-slow.txt`、`perf/s4-rn-fast.txt`。

## 场景 6 — 抽屉开合(关键场景)

**脚本:** 三包首页均执行 15 轮固定坐标“点左上角打开→800 ms→点遮罩关闭→800 ms”,
`screenrecord` 30 秒@20 Mbps,同时独立 reset/采集 gfxinfo。三包均为 30 个运动窗口。

| 包 | 录屏帧/时长 | 基准 dt | C10 运动停格 | 现代 janky | C11 爆点 |
|---|---:|---:|---:|---:|---:|
| 原生 | 1,910 / 29.15s | 8.32 ms | **21** | 1/3,810(0.03%) | 0 |
| RN | 1,906 / 29.48s | 8.33 ms | 16 | 1/3,810(0.03%) | 0 |
| anzong | 2,003 / 29.94s | 8.32 ms | **0** | 1/3,998(0.03%) | 0 |

三包 C2 janky 几乎相同,但高层 C1 明确分出关闭动画:原生/RN 多轮出现 59–208 ms
dt 大洞,anzong 30 个运动窗口全部无停格。按 C1>C2,**场景 6 不通过**,开票 53。

### 原生录屏帧表(节选)

| 帧号 | 时间戳 | dt | 异常描述 |
|---:|---:|---:|---|
| 102 | 1.635s | 77.0 ms | 关闭停格≈9.3 帧 |
| 229 | 3.459s | 78.4 ms | 关闭停格≈9.4 帧 |
| 355 | 5.321s | 175.9 ms | 关闭停格≈21.1 帧 |
| 609 | 9.215s | 119.4 ms | 关闭停格≈14.3 帧 |
| 736 | 11.158s | 167.8 ms | 关闭停格≈20.2 帧 |
| 995 | 15.034s | 193.4 ms | 关闭停格≈23.2 帧 |
| 1123 | 17.086s | 183.7 ms | 关闭停格≈22.1 帧 |
| 1377 | 20.982s | 191.9 ms | 关闭停格≈23.1 帧 |
| 1631 | 24.864s | 196.0 ms | 关闭停格≈23.6 帧 |
| 1758 | 26.916s | 185.3 ms | 关闭停格≈22.3 帧 |

视频(不进 git):`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s6-{native,rn,anzong}-drawer.mp4`。
原始 C2:`perf/s6-{native,rn,anzong}-framestats.txt`。

## 场景 5 — 横滑翻页(关键场景)

使用同一多页主题 `tid=47406116`,预热 page 1/2 后只在缓存页之间测试,避免网络耗时。
脚本为 7 个往返:左/右各 `input swipe 1050↔170 1300 500`,每次 settle 1.5 秒;
`screenrecord` 30 秒并采 gfxinfo。

`analyze_rec.py` 会把 1.1–1.36 秒的刻意静止 settle 空洞,因下一动画首帧 diff 非零而
误并入运动窗口。按 C10 手工剔除 `dt>500 ms` 的静止空洞,并按 T5 剔除每次运动窗口
起步 80 ms,再裁动画内最大 dt:

| 包 | 录屏 | 基准 dt | 运动窗口 | 最大动画内 dt | 松手等价丢帧 | 现代 janky |
|---|---:|---:|---:|---:|---:|---:|
| 原生 | 1,521帧/29.96s | 8.30 ms | 14 | **19.4 ms** | **14/14 窗口≤1** | 14/3,002(0.47%) |
| RN | 1,637帧/29.94s | 8.36 ms | 14 | 20.6 ms | 14/14 窗口≤1 | 14/3,334(0.42%) |
| anzong | 不可用 | — | — | — | — | read.php 持续“加载失败,请重试” |

原生各窗口 dt p50≈8.3 ms,速度曲线连续,松手丢帧均≤1,且不差于 RN。
anzong 已用其显式 `ArticleListActivity` 打开同一 URL,但服务链路失败,未拿失败空页冒充
横滑样本。结合 RN 现场对拍与 playbook 既有“RN 与 anzong 同形”基线,**场景 5 通过**;
anzong 现场对拍限制保留在报告中。

### 原生录屏帧表(代表窗口)

| 窗口/时间 | 动画内最大 dt | 等价丢帧 | 异常描述 |
|---|---:|---:|---|
| #1 0.992–1.682s | 19.4 ms | 1 | 连续,在闸内 |
| #4 6.202–8.184s | 18.7 ms | 1 | 连续,在闸内 |
| #6 10.513–12.594s | 17.8 ms | 1 | 连续,在闸内 |
| #9 18.308–19.002s | 18.7 ms | 1 | 连续,在闸内 |
| #14 28.534–29.792s | 17.5 ms | 1 | 连续,在闸内 |

视频(不进 git):`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s5-{native,rn}-pager.mp4`。
原始 C2:`perf/s5-{native,rn}-framestats.txt`。

## 场景 7 — 附件展开/收起

登录态打开含 2 个图片附件的主题 `tid=47328470`，定位主楼底部“点击显示附件(2)”，
执行 15 轮展开/收起、总计约 30 秒，同时录屏与 reset/采集 gfxinfo。附件缩略图均正常
加载，不是失败页或网络占位。

| 样本 | 录屏 | 运动表现 | 现代 janky | 裁决 |
|---|---:|---|---:|---|
| 原生 | 67 帧/28.87s(VFR) | 宫格与折叠条整块瞬时互换，无连续速度曲线 | **25/124(20.16%)** | **不通过** |

录屏只有点击时的单帧画面替换，点击之间的约 0.8 秒静止间隔不能按 C10 冒充动画
停格；但场景本身没有连续展开/收起动画，正文下方内容肉眼瞬移，已经违反场景 7 与
伞条款。故**场景 7 不通过**，开票 54。RN 侧相关组件历史实现同样是直接条件渲染，
不构成原生可放宽的基线；本闸要求是交互自身连续。

证据:`perf/s7-native-framestats-v2.txt`、`perf/s7-native-rec-analysis-v2.txt`；
视频(不进 git):`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s7-native-attachments-v2.mp4`。

## 场景 8 — 大图/画廊开合与缩放

使用场景 7 同一登录态附件，打开第 2 张图片；双击放大后以固定 500 ms 手势从
`(610,1600)` 平移到 `(300,1200)`，再双击尝试复位，循环约 30 秒。图片原始状态完整
可见，排除加载失败。

| 样本 | 录屏 | 现代 janky | C1 结果 | 裁决 |
|---|---:|---:|---|---|
| 原生 | 893 帧/27.57s，基准 8.31ms | 10/1,784(0.56%) | 放大后图片可整体移出视口，持续黑屏 | **不通过** |

gfxinfo 数值本身在 1% 内，但内容已经不可见；按 C1>C2，不能用持续送显掩盖交互失效。
手工再次双击有一次成功恢复，连续脚本中却无法可靠复位，故**场景 8 不通过**，开票 55。

证据:`perf/s8-native-framestats.txt`、`perf/s8-native-rec-analysis.txt`、
`perf/s8-native-post.png`；视频(不进 git):
`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s8-native-gallery.mp4`。

## 缺陷票

| 票 | 级别 | 状态 | 影响 |
|---|---|---|---|
| 51 Baseline Profile 采集为空 | P1 | 已修复并复验 | 闸前产物 |
| 52 API 36 framestats Flags=32 被全过滤 | P2 | open | C5/C6 自动分析,不阻断 C12 场景 3 裁决 |
| 53 场景 6 抽屉关闭动画停格 | P1 | open | 场景 6 不通过 |
| 54 场景 7 附件展开/收起瞬时跳变 | P1 | open | 场景 7 不通过 |
| 55 场景 8 画廊缩放后可把图片整体移出视口 | P1 | open | 场景 8 不通过 |
