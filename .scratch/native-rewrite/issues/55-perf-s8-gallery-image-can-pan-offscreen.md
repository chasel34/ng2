# 55 — 场景 8 画廊缩放后可把图片整体移出视口

**类型:** performance / interaction / acceptance blocker  
**优先级:** P1  
**Status:** resolved

## 现象

票 19 真机 release 验收中，打开 `tid=47328470` 的第 2 个附件进入大图查看器，双击
放大后执行一次单指平移，图片可整体移出视口，只剩黑色画布与系统状态/导航栏。黑屏会
持续存在；连续脚本中的后续双击没有可靠复位，用户无法继续查看图片。

这不是图片加载失败：同一图片进入查看器及手工复位后均能完整显示；问题只在缩放后的
平移边界。30 秒脚本的 gfxinfo 现代 janky 为 10/1784（0.56%），但 C1 画面证据优先于
帧率统计，内容完全移出视口直接不过闸。

## 复现

1. release 包 `com.chasel.ng2.n` 登录后打开 `ng2n://read.php?tid=47328470`。
2. 展开“点击显示附件(2)”，点第 2 张图。
3. 在查看器中央双击放大。
4. 从 `(610,1600)` 向 `(300,1200)` 平移约 500 ms。
5. 观察图片整体移出视口并停留在黑屏。

## 证据

- `acceptance/perf/s8-native-framestats.txt`
- `acceptance/perf/s8-native-rec-analysis.txt`
- `acceptance/perf/s8-native-post.png`
- 录屏（不进 git）：`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s8-native-gallery.mp4`

## 验收期望

缩放平移必须约束在图片边界，任意时刻至少保留有效图片内容；双击复位稳定可用，开合与
缩放过程无肉眼可见停格。

---

## 修复(2026-08-24)

**根因:越界位移没有上限,而「钳回边界」这件事只发生在松手那一下。**

原实现里位移分两份:`rawX/rawY` 是没打折的累计位移,写进 `graphicsLayer` 的是
`rubber(raw)` —— 越界部分乘 `EDGE_RESISTANCE`(0.55)。问题是这个折扣**线性且无上限**:
拖得够远,`0.55 × 越界量` 一样能大过「图半幅 + 视口半幅」,视口里一个像素的图都不剩。
唯一把 raw 钳回边界的地方是松手的 `settle()`,而它是 `scope.launch` 出去的一条协程,
和每一帧手势更新那些协程共享 `Animatable` 的 MutatorMutex —— 后一条手势的 `snapTo`
会把 `settle()` 里那三条 `animateTo` 抢断。也就是说「回到边界内」依赖一个可能不发生的
时序事件,连着拖几次就攒出了录屏里那 27 秒纯黑。

另外两条帮凶:

- 双击的分支判据是 `zoomed`(只看 scale)。缩放已经回到 1 而位移还没回零的状态是存在的
  (回弹被抢断就是),那时候双击会走「放大」分支,把一个本来就偏出去的位置又放大一遍 ——
  也就是票里说的「后续双击没有可靠复位」。
- 双击放大写死了 `focal × (1 - 2.5)`,隐含假设起点一定是 scale=1 / offset=0。

**改法:把边界数学抽成纯函数,并把口径从「松手时钳」改成「任何一个会被写进
`graphicsLayer` 的值都必须有界」。**

新增 `native/app/src/main/kotlin/com/chasel/ng2n/ui/image/ImageZoomMath.kt`:

- `fitDrawnSize()` —— `ContentScale.Fit` 之后图**实际画出来**的尺寸(两轴各取 min,
  不再用「算出宽再除 aspect」);
- `panBounds()` —— 某缩放档位下的边界对齐上界;这一轴上图比视口小 → 上界 0(锁死居中),
  比视口大 → `(图幅 - 视口幅) / 2`;
- `overshootLimit()` / `clampRawPan()` —— 越界额度 = 视口该轴长度 × 15%,**raw 本身**
  也钳在 `边界 + 额度` 内(顺带让手指回拖立刻跟手,不用先把多拖的那截倒回来);
- `rubberBand()` —— 阻尼改成有渐近上限的双曲式:小幅越界处斜率仍是 0.55(手感不变),
  拖到无穷也只趋近 `边界 + 额度`,真正显示出来的越界 ≤ 额度 × 0.55/1.55 ≈ 视口的 5.3%;
- `focalZoomPan()` / `nextRawPan()` —— 通用的焦点锚定式 `new = focal - (focal - old) × growth`,
  叠完平移**当帧**按新 scale 的边界重钳(缩小的那一帧当场收,不留残余位移)。

`ImageZoom.kt` 的 `ZoomState` 只改了接线,手势分流(`detectViewerTransform` 的
「单指翻页 / 放大后拖图 / 双指捏合」三路)和 220ms 回弹一个字没动:

- `onGestureUpdate()` 用 `nextRawPan` + `rubberBand`,边界按**这一帧之后**的 scale 算;
- 新增 `atFit`(scale 回到 1 **且** 位移回到中心),双击的判据从 `zoomed` 换成它 ——
  「不在适配位就一定回适配位」,从任意 scale/offset 状态双击都能落回适配位;
- 双击放大改用 `focalZoomPan(growth = 2.5 / 当前 scale)`,不再假设起点;
- `reset()` 先把 raw 归零再跑动画:动画哪怕被下一条手势抢断,下一次手势也不会从一个
  越界值继续累计。

**单测**:`native/app/src/test/kotlin/com/chasel/ng2n/ui/image/ImageZoomMathTest.kt`,19 条。
除了逐条钉死取值(适配档位两轴上界为 0、放大后上界 = 边缘对齐、图比视口小的轴锁死居中、
宽高比未知/视口未量出的退化档、脏数据),核心是一条不变量:**视口里的图不少于居中时的 85%**,
拿四个象限 × `aspect ∈ {0.02, 0.1, 0.5, 1, 3, 20, 60}` × `scale ∈ {0.6, 1, 1.5, 2.5, 4}`
把 `raw = ±1e6` 全扫一遍;外加一条按票面复现回放的用例(双击 2.5× 后照 `(610,1600)→(300,1200)`
连拖十次、每次拆 30 帧派发),中途每一帧都断言可见比例 > 85%,松手后回到边缘对齐。

**验证**:`./gradlew :app:assembleDebug :app:testDebugUnitTest` 绿。

**待真机复验**(本轮无真机):按票面复现步骤跑一遍 —— 双击放大后单指猛拖到四个方向,
确认任何时刻视口里都有图;再连拖十次后双击,确认一次回到适配位。gfxinfo 那半不用重跑,
本票判的是画面内容不是帧率。
