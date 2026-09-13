# 界面动效

动效参考 [Transitions.dev](https://transitions.dev/detail.html?t=page-side-by-side)。2026-09-13 复核详情页内完整目录，共 43 项：读取全部公开说明，并检查相关免费 CSS / React 模板；Pro 条目按公开预览说明判断适用性，不依赖付费代码。以下为 Compose 原生适配，不直接运行网页代码。

## 使用原则

阅读内容保持稳定。转场表达页面层级、展开关系和状态变化；不对正文、楼层列表逐项入场，不引入全屏模糊、粒子或循环装饰动画。Compose 动画使用平台提供的动画时长缩放；关闭动画时状态仍能完成切换，不靠固定延时移除遮罩。

时长集中在 `ui/common/Motion.kt`：页面 260ms、菜单 160ms、一般状态 200ms、提示条 220ms、轻量退场 140ms。页面前进从右侧进入、返回反向，两页以完整容器宽度同步平移并保持不透明，避免中间帧露出浅色底和文字重叠。应用通过 Manifest 的 `android:enableOnBackInvokedCallback="false"` 关闭预测返回，不配置预测返回转场；图片查看器保留独立淡入淡出，避免和缩放、拖拽手势竞争。

菜单外层同步缩放内容与系统投影，投影颜色与内层内容透明度跟随同一动画进度；投影位于内容透明度层之外，避免离屏合成裁切阴影后在结束时突现。菜单与弹窗在退场完成后移除，退场期间隐藏子项无障碍动作并消费点击，防止穿透或重复提交。通用弹窗、设置选择、屏蔽规则、跳页、书签、签名共用弹窗外壳；收藏文件夹保留全屏嵌套创建弹窗的布局，关闭时整体淡出。表单临时状态随内容销毁，设置选择每次打开重新读取当前值。

## 参数与代码入口

时长单位为毫秒，缩放值为比例。调整动画优先修改 [Motion.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/Motion.kt) 的共用参数。

| 场景 | 参数与行为 | 实现入口 |
|---|---|---|
| 页面前进 / 返回 | 260ms，`easeDecelerate`，全宽横移；返回方向反转 | [MotionTransitions.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/MotionTransitions.kt)、[Ng2nApp.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/Ng2nApp.kt) |
| 右上角菜单 | 入场 160ms、退场 140ms，缩放 0.94 → 1；右上角固定 | [OverflowMenu.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/OverflowMenu.kt)、[TopicOverlays.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicOverlays.kt) |
| 共用弹窗 | 入场 200ms、退场 140ms，缩放 0.94 → 1，遮罩与内容淡化 | [Dialogs.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/Dialogs.kt) 的 `DialogShell` |
| 提示条 | 220ms，14dp 上移与淡入；全局提示附轻缩放 | [Snackbar.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/Snackbar.kt)、[TopicOverlays.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicOverlays.kt) |
| 图标切换 | 入场 200ms、退场 140ms，缩放 0.8 ↔ 1 与交叉淡化 | [MotionIcon.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/MotionIcon.kt) |
| 状态文字 | 200ms，位移为文字容器高度的 1/3；旧文字 140ms 淡出 | [MotionTransitions.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/MotionTransitions.kt) 的 `MotionTextSwap` |
| 输入校验 | 错误信息引起的高度变化 200ms，横向轻抖仍配明确错误文字 | [Dialogs.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/common/Dialogs.kt) |
| 折叠内容 | 高度与淡化 200ms，箭头随展开状态连续旋转 | [Blocks.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/bbcode/Blocks.kt)、[BBCodeIcons.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/bbcode/BBCodeIcons.kt) |
| 未读角标与签到状态 | 角标开合、数字和状态文字切换 | [AppDrawerContent.kt](../app/src/main/kotlin/com/chasel/ng2n/ui/drawer/AppDrawerContent.kt) |

## 扩展与维护

新增弹窗优先复用 `DialogShell`。可空业务对象驱动弹窗时，关闭后保留退出画面所需的数据，直到动画结束再销毁；不要在 `open=false` 时立即删除整个 Composable。`rememberVisibilityTransition` 支持动画途中反向切换；只有当前状态和目标状态都关闭且动画停止时才移除覆盖层。

退场中的覆盖层调用 `guardExitingOverlay` 隐藏动作语义并消费触摸事件。动画是否完成应由 Transition 状态决定，避免用固定 `delay` 控制卸载，否则系统关闭动画或连续开关时容易残留遮罩。

页面两侧必须使用相同位移幅度、时长和曲线，且保持页面背景不透明。不要叠加整页交叉淡化，否则中间帧会混入浅色根背景，造成白闪和文字重叠。

菜单的系统阴影应在内容的 alpha / clip 层之外绘制，再由共同外层缩放；阴影颜色透明度与内容共享 `pop` 进度。不要把系统投影放进内容透明度层，否则圆角外阴影可能被离屏合成边界裁掉，并在动画结束时突然出现。

## 全目录筛选

“采用”包含新增适配和保留现有的等价动效，不表示照搬每一种装饰细节。

| 参考动画 | 决定与项目落点 |
|---|---|
| Card resize | 采用：输入提示变化使用 `animateContentSize`；折叠内容继续使用高度动画。 |
| Number pop-in | 不单独采用翻转、模糊、逐位延迟；通知数字使用下述文字切换，保持小字号可读性。 |
| Notification badge | 采用：抽屉未读角标淡入、轻缩放与短距离上方滑入，清零时退场。 |
| Text states swap | 采用：签到状态、未读计数和全局提示文字短距离上下切换。 |
| Confetti burst | 不采用：常规论坛动作无需庆祝粒子。 |
| Menu dropdown | 采用：全局及帖子菜单以触发侧上角为缩放原点，补齐退场。 |
| Modal open/close | 采用：共用弹窗缩放及遮罩淡入淡出；文件夹嵌套弹窗整体淡出。 |
| Panel reveal | 保留：抽屉跟手滑动及开合、帖子快捷操作面板的上移动效。 |
| Gooey plus menu | 不采用液态形变；现有快捷操作展开足以表达层级。 |
| Page side-by-side | 采用：全局 Navigation 3 普通前进和返回转场定义，图片查看器除外。 |
| Card stack hover | 不采用：触屏阅读列表没有悬停卡片堆。 |
| Icon swap | 采用：顶栏动态图标、投票选项、收藏文件夹与正则选项的选择图标。 |
| Success check | 不增加独立成功对勾：现有成功提示文字及选择图标已反馈结果。 |
| Avatar group hover | 不采用：没有头像组悬停交互。 |
| Error state shake | 采用：通用输入框校验错误轻微横向抖动，保留明确错误文字。 |
| Organic shimmer | 不采用：循环装饰会分散阅读注意力。 |
| Input clear with dissolve | 不采用：清空搜索应立即显示空值，不延迟输入反馈。 |
| Skeleton loader and reveal | 不引入猜测正文布局的骨架：帖子长短与 BBCode 结构未知，保留既有加载页；不延长等待。 |
| Texts reveal | 不对帖子正文或行文本错峰入场：会干扰阅读定位。 |
| Tabs sliding | 保留：首页分类指示条插值跟随 Pager 拖拽和点击切换。 |
| Drag & drop with physics | 不采用：没有拖入文件的业务流程。 |
| Image open tilt | 不采用 3D 扭曲：保留图片查看器淡入及已有缩放、翻页手势。 |
| Shimmer text | 不采用：状态文字需要稳定可读。 |
| Tooltip open/close | 不增加悬停提示层：触屏按钮已有标签及无障碍描述。 |
| 3D tilt | 不采用：没有指针跟随场景。 |
| Dropdown menu morph | 不采用按钮变形成菜单：全局与帖子菜单采用同一锚点缩放规则。 |
| Accordion | 采用：热门回复箭头连续旋转；正文折叠高度与透明度统一 200ms 节奏。 |
| Toast open/close | 采用：全局与帖子提示条淡入上移、保留到退场完成；全局提示附轻缩放。 |
| Like button | 不采用心形粒子：论坛赞踩不是心形收藏，保留明确动作语义。 |
| Learn more hover | 不采用：没有悬停入口。 |
| Checkbox check | 适配：投票、文件夹、正则勾选采用既有图标之间的缩放交叉淡化，保持图标系统一致。 |
| Spinner to check morph | 不采用：加载页面完成后直接展示内容，不插入额外成功等待。 |
| Spinning counter | 不采用：楼层号、票数及页码保持可扫描，未读数用短文字切换。 |
| Toggle | 保留：设置开关滑块及轨道颜色连续过渡，不增加二次弹跳。 |
| Pro gradient text | 不采用：没有推广渐变文字场景。 |
| Delete with smoky dissolve | 不采用：移除内容要及时反馈，烟雾会遮挡相邻项目。 |
| Thinking states | 不采用：没有 AI 推理过程；请求状态沿用明确文案。 |
| Reasoning stream | 不采用：没有推理流。 |
| Streaming text | 不采用：论坛返回完整正文，无逐词生成语义。 |
| Matrix dot loader | 不采用：已有统一加载指示器，无需新增一套循环反馈。 |
| Banner stacking | 不采用：提示继续展示最新一条，避免遮挡正文。 |
| Image generation placeholder | 不采用：没有图片生成流程。 |
| Get Pro button | 不采用：没有 Pro 购买入口。 |

## 验证

新增 Compose UI 测试依赖仅用于 debug / androidTest，由现有 Compose BOM 管理版本。测试检查页面方向、中间帧、弹窗退场销毁与反向切换、菜单退出语义、提示替换、选择取消后重开及无动画状态完成；真实应用冒烟覆盖首页、抽屉、设置、主题弹窗及返回。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.chasel.ng2n.ui.common
```

设备测试把图片保存到调试应用外部文件目录下的 `motion-verification/`。模拟器检查用于功能和画面验证，不作帧率或真机性能结论；真机 release 性能判据仍以 [性能手册](perf-playbook.md) 为准。


### 真机复测方法

[MotionVisualSmokeTest](../benchmark/src/main/kotlin/com/chasel/ng2n/benchmark/MotionVisualSmokeTest.kt) 操作手机上已安装的正式包：打开网事杂谈，连续开关菜单，进入浏览历史和书签并返回。执行前保持手机解锁，准备好已授权调试的设备；使用明确的设备序列号，避免操作其他已连接设备。场景不执行签到、发帖、投票或云端收藏等账号写操作。

```bash
./gradlew :app:assembleRelease :benchmark:assembleBenchmarkRelease
adb -s <真机序列号> install -r app/build/outputs/apk/release/app-release.apk
adb -s <真机序列号> install -r benchmark/build/outputs/apk/benchmarkRelease/benchmark-benchmarkRelease.apk
adb -s <真机序列号> shell am instrument -w \
  -e class com.chasel.ng2n.benchmark.MotionVisualSmokeTest \
  com.chasel.ng2n.benchmark/androidx.test.runner.AndroidJUnitRunner
```

录制应用内操作，逐帧检查页面前进与返回的中间画面、菜单入场与退场、菜单完全展开后的投影。测试通过只说明导航操作完成；白闪与阴影是否同步还需要核对录屏。录屏包含的本地内容仅作为本地诊断证据保存。

### 本次验证结果（2026-09-13）

- 初次动效适配：JVM 离线测试 1,107 项通过，4 项联网测试按开关跳过；模拟器 Compose 设备测试 12 项通过。
- 真机问题修复后：release 构建及覆盖安装成功，真机导航测试 1 项通过；模拟器页面不露底、前进返回方向、菜单退场点击保护和应用导航冒烟共 4 项通过。
- 在真机 25113PN0EC 的 release 包中，对比相同导航操作的修复前后录屏，所测场景未再观察到页面白闪；菜单入场中已有柔和外投影，完全展开后保留阴影，不再结束时突现。
- 预测返回保持关闭。上述结果不代表其他设备的表现，不包含 FPS 或功耗结论。
