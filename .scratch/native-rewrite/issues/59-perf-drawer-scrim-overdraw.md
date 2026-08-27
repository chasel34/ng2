# 59 — P2:抽屉遮罩整屏混合 + 面板底色画两遍(纯 overdraw)

**Status:** reopened（视觉 verified；GPU p95 硬闸未过 —— 二轮归因认为该闸口径本身要改判，见「二轮削 GPU」第一节第 4 条）

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

## 二轮削 GPU(2026-08-27)

一轮把 scrim 的 73.7% 裁掉、面板底色的重复填充去掉,视觉 verified,但六轮抽屉里
五轮 GPU fence p95 仍 7.097–8.084ms、max 8.816–10.731ms。本节先逐项把「抽屉动画每帧
的 GPU 成本到底花在哪」拆开,再说改了什么。

### 一、逐项归因

抽屉动画的每一帧,`DrawerHost` 这一路要画四样东西。逐项算:

| 项 | 面积(1220×2712 屏,面板 900px) | 结论 |
|---|---|---|
| 整屏首页(背景层) | 3.31Mpx,其中 2.44Mpx 压在不透明面板底下 | **是本轮的大头,已裁** |
| 面板底色 + 14 行正文 | 900×2712 = 2.44Mpx 不透明填充 + 15 段文字 / 15 颗图标路径 | 必要开销,不动 |
| `.shadow(Elevation.level2)` | 只有右缘一条 ≈ 2712×70px 上屏 | **不是大头**(下详) |
| 残余 scrim 条 | 321×2712 = 0.87Mpx alpha 混合 | 一轮已压到最小 |

#### 1. 层缓存:面板享受了,首页没有

- **面板**:`.graphicsLayer { translationX = … }` 在**层阶段**读 `progress`,`.shadow()`
  又在里面套了第二颗层;底色与 `drawerContent()` 都录在内层的 display list 里。
  动画期间只有层属性在变,**内容不重录**。
- **首页**:整棵子树身上一个 `graphicsLayer` 都没有 —— 它的绘制指令是直接录进**祖先层**
  (最终是 `AndroidComposeView` 那颗)的 display list 的。而遮罩的 `drawBehind` 每帧读
  `progress`,把那颗祖先层标脏 ⇒ **整棵首页每帧重录一次**。两边待遇不对等,这就是
  票 58 表里 RT `Drawing` 抽屉链稳定在 1.76–2.12ms 的来源。
- 重录是 CPU;但同一处还藏着 GPU:首页那 2.44Mpx **完全被不透明面板盖住**的部分,
  每帧照样进光栅。一轮只裁了遮罩(一层 alpha 混合),没裁真正贵的那层。

#### 2. `.shadow(level2)` 不是大头

elevation 阴影由**父层在画子 RenderNode 时**发出。HWUI 的 `RenderNodeDrawable::drawShadow`
按 caster 的 alpha 决定要不要带 `kTransparentOccluder_ShadowFlag` —— Compose 这颗层
alpha 恒为 1,走的是**不透明遮挡物**那一支,Skia 因此只画外圈那一圈模糊环,不填面板内部
(这个 flag 存在的意义就是「遮挡物不透明时不画它底下那块」)。再叠上几何:面板贴左、
满高摆放,上/下/左三条环整条都在窗口外被 scissor 掉,**真正上屏的只有右缘那一条
≈ 2712×70px ≈ 0.19Mpx**。

这条推断依赖 Skia 内部实现、静态代码给不出证据,所以留了一个默认 `true` 的编译期常量
`PerfFlags.DRAWER_PANEL_SHADOW` 当**对照实验**(改 false 是有视觉差的,不是可发布档位),
真机上一次 A/B 就能把「阴影几毫秒」钉死。见下「三、复验口径」第 3 条。

不做「预渲染渐变条 / 静态 9-patch 替代」:那是**看起来差不多**,不是逐帧等价,而本票
三配色刚 verified,不许回退。

#### 3. 表里那两条老候选

- **`windowBackground` 首帧后置空**:真 overdraw(DecorView 每帧一次 3.31Mpx 满屏不透明
  填充),但等价性取决于「Compose 侧任何一帧都铺满不透明底色」,`NavDisplay` 转场中途 /
  Activity 重建 / 系统缩略图截取有没有空档,静态看不出来;HWUI 又是「谁都不画就留上一帧
  残留」,赌错就是黑闪或残影。按票面要求做成**编译期开关、默认关**:
  `PerfFlags.BLANK_WINDOW_BACKGROUND_AFTER_FIRST_FRAME`。开启与验法写在该常量的 KDoc 里,
  并在下面「三」里复述。`PerfFlagsTest` 钉住两个默认值,防止实验完忘了改回来。
- **设置转场两屏同画**:要动导航结构,不在本票范围,本轮不做。

#### 4. 但真正的触发帧,画的东西并不多 —— 这是本轮最重要的一条

票 58 裁定第五节点名了触发第三次翻面的那一帧:**t=10882.54 抽屉开首帧,GPU 9.33ms**。
把这一帧的几何摊开看:

- 抽屉是从**点顶栏菜单钮**开的,`settle(scope, true)` 走 `animateTo(1f)`,首帧的
  `progress ≈ 0`;
- 面板 `translationX = -(1-progress)*900 ≈ -900`,也就是这一帧面板**整块在窗口外**,
  被 scissor 掉;遮罩 `alpha = progress ≈ 0`;
- 于是这一帧上屏的像素**和一帧普通首页一模一样** —— 而普通首页那条链(tab 切换窗口)
  的 GPU 中位只有 **1.31ms**。

同一份 trace 里紧接着的两帧写得清清楚楚:

```
10887.06  waiting for GPU completion 1328   dur=9.33
10899.85  waiting for GPU completion 1329   dur=1.11
10903.98  waiting for GPU completion 1330   dur=1.60
```

**同样的内容,1.11ms 与 9.33ms 差 8 倍。** 结论只能是:`waiting for GPU completion`
量到的**不全是这一帧的光栅工作**,它同时含着「RenderThread 领先 GPU 多远」——
也就是票 58 判定的那个队列深度状态本身。合并重测的分段表是同一件事的第二个证据:

| 窗口 | 高峰率(管线档位) | GPU p95 |
|---|---:|---:|
| 抽屉 1/2/3/5/6 | 100%(H 档) | 7.097–8.084ms |
| **抽屉 4** | **0.9%(L 档)** | **2.814ms** |
| 设置返回 | 1.2%(L 档) | 8.326ms |

抽屉 4 与另外五轮**画的是同一套东西、同一段动画**,只因为管线在 L 档,p95 就从 ~7.5ms
掉到 2.814ms——已经在本票 `<6ms` 闸内。而 L 档下真花力气的设置返回(转场两屏同画)
仍有 8.326ms。也就是说:**H 档下 GPU fence 有一个 ~7ms 的地板,与画什么无关;L 档下它
才反映真实工作量,而抽屉的真实工作量本来就够用。**

这把票 59 的立票前提(「抽屉每帧 GPU 光栅超预算」)**证伪了一半**:overdraw 确实存在、
确实该削(一轮 + 本轮都削了),但它不是 7–8ms 读数的成因,所以**光靠削 overdraw 不可能
把 H 档窗口的 p95 压到 6ms 以下**。复验口径必须先把这两件事分开量,见「三」。

### 二、改动

`native/app/src/main/kotlin/com/chasel/ng2n/`

- **`ui/drawer/DrawerHost.kt`** —— 首页那一层加两个修饰符,**顺序是有意的**:

  ```kotlin
  Modifier
    .fillMaxSize()
    .drawWithContent { …clipRect(left = drawerScrimLeft(progress, widthPx, size.width))… }
    .graphicsLayer()
    .drawerDrag(…)
  ```

  1. `drawWithContent` + `clipRect`:首页也只画面板右缘之外那一条,左边那 73.7%
     的绘制指令被 Skia 直接 quickReject,不进 GPU 光栅。左边界复用一轮那个
     `drawerScrimLeft` —— 两刀是**同一条边界、同一套论证**(含同一个 1px 接缝保护)。
  2. `graphicsLayer()`:给首页一颗自己的 RenderNode。修饰符链从左到右是外到内,
     所以 clip 挂在层**外面**:每帧只重录「一个 clip + 一次 drawRenderNode」,
     首页整棵子树的 display list 原样复用,不再被遮罩的 `drawBehind` 拖着重录。
  3. 面板的 `.shadow(...)` 换成 `.then(if (PerfFlags.DRAWER_PANEL_SHADOW) … else Modifier)`,
     默认 `true` = 与修前逐字节相同(`const val`,编译期就折掉,没有运行期开销)。
- **`ui/perf/PerfFlags.kt`(新增)** —— 两个编译期测量口子,默认值 = 当前发布行为:
  `BLANK_WINDOW_BACKGROUND_AFTER_FIRST_FRAME = false`、`DRAWER_PANEL_SHADOW = true`。
- **`MainActivity.kt`** —— 上面第一个常量为 `true` 时,连等两个 `withFrameNanos`
  (回调发生在开画之前,等到第二次时第一帧已经进过 DecorView 的 display list)后
  `window.setBackgroundDrawable(null)`。默认关。
- **`ui/drawer/DrawerGesture.kt`** —— `drawerScrimLeft` 的 KDoc 改写成「**被面板整块盖住
  的那段的右边界**」:它现在有两个调用点(遮罩、首页),论证是同一条。
- **`DrawerGestureTest`** —— 加 2 条:裁剪边界在 0→1 的 101 个采样点上**恒不越过面板右缘**
  (越过一列就露底,是可见性缺陷而不是省钱)、且**随进度单调不减**(倒退会让上一帧已省
  掉的一条突然要重画,表现为动画中段一次 GPU 尖峰)。
- **`PerfFlagsTest`(新增)** —— 钉死两个开关的出厂值。
- **`scripts/perf/analyze_s9_segments.py`** —— 加 `--by-peak`:把 GPU fence 时长按
  「所属帧落在低峰还是高峰」分桶统计。这是本轮归因结论的**判据脚本**,见下。

### 三、为什么仍然是逐帧等价

- **首页裁剪**与一轮的遮罩裁剪是**同一条几何、同一套论证**:被裁掉的
  `[0, 面板右缘 - 1px)` 每一个像素都压在不透明面板底下(`colors.surface` 三套配色
  `0xFFFFFBF0` / `0xFF232322` / `0xFFFFFFFF` 全不透明,面板在同一个父 `Box` 里排在
  首页之后、绘制序在上)。1px 接缝保护同样往面板底下多吃一列,面板右缘的抗锯齿列
  底下仍然有首页垫着,合成结果不变。单测把这两条钉住了。
- 裁掉之后 `[0, 左边界)` 那块由 `windowBackground`(不透明)兜底、再被不透明面板盖住,
  两层都在同一帧里,没有「谁都不画」的空档 —— 这也正是**不能同时开
  `BLANK_WINDOW_BACKGROUND_AFTER_FIRST_FRAME` 又不验真机**的原因。
- `graphicsLayer()` 全默认参数(alpha=1、clip=false、无 RenderEffect),不引入离屏合成,
  只改「这棵子树录在哪颗 display list 里」,像素不变。
- **命中测试一点没动**:`drawWithContent` / `graphicsLayer` 都只管画;遮罩仍是满屏
  `clickable` + `semantics`,首页那一层的 `drawerDrag`(左边缘 22dp 让位规则)原样在
  层里面,坐标不经过任何新变换。
- `PerfFlags` 两个常量默认值 = 修前行为,默认构建下这两处代码等于不存在。

### 四、复验怎么判(给 codex 的对拍口径)

同一台 `25113PN0EC`、同 release/`speed-profile` 包、同一份 15 秒脚本
(设置返回 + 6 轮抽屉开合)、同 `s9-native-rich.cfg` 富 trace。测前后按 T4 验前台、
运动中按 T1 验 120Hz。

1. **先把「光栅工作」与「队列深度等待」分开**(本轮新加,**这一条最重要**):

   ```bash
   scripts/perf/.venv/bin/python scripts/perf/analyze_s9_segments.py s9.pb \
     --package com.chasel.ng2.n --by-peak
   ```

   看尾部的 `low-peak frames` / `high-peak frames` 两行。判据:
   - 若 **high 桶 p50 显著高于 low 桶 p50(≳3ms)**,则 GPU fence 在 H 档下是队列深度的
     代理量,本票 `p95<6ms` 这个闸**量的不是光栅**,应当改判为「只在 low-peak 帧上判」
     (见第 2 条),并把结论回写票 58;
   - 若两桶接近,则本轮归因错了,GPU fence 确实是工作量,回来继续削面板那一路。
2. **本轮改动的实际收益**,用 **low-peak 帧**上的 GPU fence 分布对拍(修前基线:
   `acceptance/perf/s9-final-gpu-analysis.txt` 整体 p50/p95 5.043/7.695ms;
   抽屉 4 那个 L 档窗口 p95 2.814ms)。期望:low-peak 桶的 p50/p95 下降,
   抽屉窗口在 L 档下的 p95 稳定 <6ms。
3. **阴影 A/B**(可选,只为把第 1 节第 2 条的推断钉死):把
   `PerfFlags.DRAWER_PANEL_SHADOW` 改成 `false` 重打一包,同脚本再采一次,
   比 low-peak 桶的 GPU p50 差值 = 阴影的钱。**量完必须改回 `true`**
   (`PerfFlagsTest` 会拦)。
4. **`windowBackground` 置空**(可选,票面第 3 条要的真机验证):把
   `PerfFlags.BLANK_WINDOW_BACKGROUND_AFTER_FIRST_FRAME` 改成 `true` 重打,
   冷启动 → 首页 → 抽屉开合 → 进设置 → 返回 → 切 tab → 切后台再回来 → 切系统深色模式,
   **全程录屏逐帧看黑闪 / 残影 / 半屏旧内容**。没有闪再采 trace 对拍 GPU 分布;
   出现任何一次闪就改回 `false` 并把现象记在这里,别留半开状态。
5. **硬闸不回退**:present cadence 中位 8.32±0.5ms、无连续丢 >2 vsync。
6. **视觉**:抽屉开/关全程与修前逐帧一致。本轮新增了**首页裁剪**,所以重点看
   **面板右缘那一列**在三套配色下有没有亮边/暗边/缺口(一轮已按同一口径截过
   `acceptance/perf/t59-drawer-{ink,plain,night}.png`,直接对拍这三张);
   另外看拖动到一半停住时,面板右侧的首页内容有没有被裁多。
