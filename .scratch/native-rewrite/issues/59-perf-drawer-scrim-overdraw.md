# 59 — P2:抽屉遮罩整屏混合 + 面板底色画两遍(纯 overdraw)

**Status:** reopened（视觉 verified；GPU p95 硬闸未过）

**Severity:** P2（不是节奏缺陷，是每帧 GPU 预算余量；由票 58 裁定第 4 条拆出）

## 来由

票 58 用 43MB 富 trace（`s9-rich.pb`，SHA-256 `f7da877d…0ac3eb`）判定：场景 9 的
FrameTimeline 双峰是**系统侧一个二值、粘滞的管线深度状态** —— 一次 GPU 单帧撑爆
8.333ms 预算 → SF 在 `transactionReadyTimelineCheck` 里把 app 的 transaction 押后一档
（`onCommitNotComposited`）→ 之后 app 仍 1 帧/vsync 生产，队列**再也排不空**。15 秒里
只翻面 3 次，唯一一次自愈是靠掉了一帧把队列冲干净；两侧的自动缓解
（HWUI `isSwapChainStuffed()` 靠 dequeue 变慢、SF 的 stuffing 判定）都因为**谁都不超时**
而不触发，app 没有直接 API 让 SF 排空队列。

裁定第五节给出 app 侧唯一一根真杠杆 —— **把抽屉/设置路径的每帧 GPU 光栅压回预算内，
让「撑爆预算」这个触发条件本身不成立**：

| 窗口 | GPU 中位 | GPU p95 | GPU max | >8.333ms 帧数 | RT `Drawing` 中位 |
|---|---:|---:|---:|---:|---:|
| 抽屉-1 | 5.45 | 8.01 | 8.66 | 2 | 1.76 |
| 抽屉-2+滚动+进设置 | 6.38 | 8.20 | 10.98 | 4 | 1.79 |
| 设置 | 6.21 | 10.29 | 11.92 | 3 | 1.61 |
| **tab 切换** | **1.31** | **2.23** | 9.69 | 1 | 2.09 |
| 抽屉-2 | 5.30 | 7.22 | **9.33** | 2 | 2.12 |
| 抽屉-3 | 5.59 | 8.03 | 8.48 | 2 | 1.91 |

RenderThread 的 CPU 侧录制两边一样（1.6–2.1ms），差的**全是 GPU 光栅**：抽屉链是
tab 链的 4 倍，p95 已经贴着预算。触发第三次翻面的就是 t=10882.54 抽屉开首帧的
GPU **9.33ms**（当时管线还在 L 态，对照组成立）。

数据来源：`acceptance/perf/s9-native-rich-analysis.txt`、
`issues/58-perf-s9-frametimeline-double-peak.md` 裁定段。

## 现象(静态代码分析，几何可算)

设备 `pudding / 25113PN0EC`：屏 1220×2712px、密度 3.0，面板 300dp = 900px。
抽屉动画的每一帧，`DrawerHost` 这一路要画：

1. **整屏 scrim alpha 混合** —— `drawBehind { drawRect(colors.scrim, alpha = progress) }`
   铺满 1220×2712 = 3.31Mpx。而面板底色 `colors.surface` 三套配色全是 `0xFF…`
   不透明、又画在 scrim **之上**：开到底时 `[0, 900px)` 这 **73.7%**（2.44Mpx）
   的 scrim 每帧都被整个盖掉，**画了等于没画**。
2. **面板底色画两遍** —— `DrawerHost` 的面板 Box 已经 `.background(colors.surface)`，
   `AppDrawerContent` 的根 `Column` 又来一次 `.fillMaxSize().background(colors.surface)`。
   同一块 900×2712 = 2.44Mpx 的不透明填充，每帧提交两次。

合计每帧约 **4.9Mpx** 的多余 fragment 工作，在一块总共 3.3Mpx 的屏上 —— 与
「抽屉链 GPU 是 tab 链 4 倍」的量级自洽（tab 切换没有 scrim、没有第二层满屏底色）。

## 改动

`native/app/src/main/kotlin/com/chasel/ng2n/ui/drawer/`

- **DrawerGesture.kt** — 新增纯函数 `drawerScrimLeft(progress, widthPx, containerWidthPx)`：
  面板贴左摆、`translationX = -(1-progress)*widthPx`，右缘恒在 `progress * widthPx`；
  遮罩从这里往右画到容器右缘。新增常量 `DrawerGeometry.SCRIM_SEAM_GUARD_PX = 1f`。
- **DrawerHost.kt** — `drawBehind` 改成 `drawRect(topLeft = Offset(left, 0f), size = …)`，
  只画面板右缘之外那一条。面板 `.background(colors.surface)` 处补了因果注释：
  遮罩裁剪成立的前提是「面板整块不透明」，改半透明就要一起撤。
- **AppDrawerContent.kt** — 摘掉根 `Column` 上重复的 `.background(colors.surface)`
  （底色由 `DrawerHost` 的面板层负责，只画一次）。
- **DrawerGestureTest.kt** — 6 条几何单测钉死裁剪：跟随面板右缘、开到底省掉 73.7%、
  progress→0 时从 0 画起、面板比容器宽 / 容器未测量 / progress 越界三种夹取。

### 为什么是逐像素等价，不是「看起来差不多」

- 被裁掉的 `[0, 面板右缘)` 区间，**每一个像素**都压在不透明面板底下。`colors.surface`
  在 `LightColors` / `DarkColors` / `PlainColors` 三套里分别是 `0xFFFFFBF0` /
  `0xFF232322` / `0xFFFFFFFF`，全不透明；面板 Box 在同一个父 `Box` 里排在遮罩之后，
  绘制序在上。
- **接缝那一列**是唯一要留神的地方：面板靠 `graphicsLayer` 平移，右缘落在小数像素上，
  那一列是抗锯齿出来的半透明；`drawRect` 的边界同样抗锯齿。左边界正好切在面板右缘的话，
  这一列会从「首页 → 遮罩 → 面板边缘」变成「首页 → 面板边缘」，差一个亚像素。
  所以左边界往面板底下多吃 1px（`SCRIM_SEAM_GUARD_PX`）：`ceil(e-1) = floor(e)`，
  面板抗锯齿列 `[floor(e), ceil(e)]` 因此完整落在遮罩的满覆盖区里。代价是少省 1/1220。
- 命中测试**一点没动**：`drawBehind` 只管画，遮罩节点的 `clickable` / `semantics`
  仍是满屏，「点遮罩关抽屉」的可点区域与修前完全一致。
- 面板底色那一层是**同色同区域**的重复填充，去掉后合成结果按定义不变；`AppDrawerContent`
  全项目只有 `HomeScreen` 一处调用，且必在 `DrawerHost` 的面板里。

## 同路径其他 overdraw:查了，这轮不动

| 候选 | 判断 |
|---|---|
| 对话框遮罩（`Dialogs.kt` / `SettingsUi.kt` / `FilterRuleDialog.kt`） | **不裁**。面板是圆角、且入场期间 `alpha = pop < 1`，遮罩从底下透出来 —— 裁掉**不是**可见性等价。且场景 9 的 15 秒脚本里根本没开对话框，不在这条 GPU 曲线上。 |
| 设置转场的整屏叠加 | **没有**。`SettingsShell` 只有一层 `.background(colors.bg)`，`NavDisplay` 用默认转场、不额外铺遮罩。设置窗口的 GPU 成本来自「转场期间两屏同时在画」，削它要动导航结构 = 大重构，不在本票范围。 |
| 面板 `.graphicsLayer{translationX}` 与 `.shadow(level2)` 并成一层（票 58 候选 2） | **不做**。它省的是一个 RenderNode（CPU 侧），而 trace 里 RT `Drawing` 高低峰一样（1.6–2.1ms），瓶颈不在那儿；`shadow()` 默认 `clip = elevation > 0`，合并要手写 `clip`/`shape`，风险大于收益。 |
| `android:windowBackground` 首帧后置空 | **不做**。是真 overdraw（DecorView 每帧一次满屏填充），但它的等价性取决于「Compose 侧任何一帧都铺满不透明底色」，`NavDisplay` 转场中途有没有空档**没有真机验不了**，赌错就是黑闪。记在这里，等真机复验时顺带量。 |

## 构建与测试

```
cd native && ./gradlew :app:assembleDebug :app:testDebugUnitTest -q
```

绿。`DrawerGestureTest` 含新增 6 条。

## 待真机复验

同一份 15 秒脚本、同 release/`speed-profile` 包，重采富 trace
（`acceptance/perf/s9-native-rich.cfg`）对拍：

1. **GPU fence**（`waiting for GPU completion` slice 时长）：抽屉三个窗口
   **p95 < 6ms、max < 8.333ms**（今天 p95 8.01/8.20/7.22/8.03、max 8.66/10.98/9.33/8.48）；
2. **场景 9 双峰**：高峰（`SF start→present` 17–20ms）占比下降；票 58 裁定第 3 条已建议
   把这一项降为**观测项**——它取决于「本次会话有没有恰好掉过一帧」，不作硬闸；
3. **硬闸不回退**：present cadence 中位 8.32±0.5ms、无连续丢 >2 vsync；
4. **视觉**：抽屉开/关全程与修前**逐帧一致**（重点看面板右缘那一列有没有亮边/暗边，
   以及面板底色在浅色/深色/纯白三套配色下是否仍然实心）。

顺带量一下 `windowBackground` 置空的可行性（见上表最后一行）。

## 合并复验(2026-08-27)

三套配色的抽屉全开态均已真机截图对拍：墨绿经典、纯白、夜间近黑的面板右缘都没有
亮/暗接缝，面板底色实心且没有双重底色；视觉项 **verified**。截图为
`acceptance/perf/t59-drawer-{ink,plain,night}.png`，验后已恢复墨绿经典并重新打开
「夜间模式跟随系统」。

但同包场景 9 富 trace 的整体 GPU p95 为 **7.695ms**，六轮抽屉有五轮 p95
7.097–8.084ms，max 8.816–10.731ms；仍不满足本票 `<6ms` / `<8.333ms` 两个硬闸。
所以本票不能因视觉等价就判整票 verified，状态改为 reopened；逐段与双峰归因见票 58
「票 59 后合并重测」。
