# 性能历史参考

本页保存旧实现、旧设备采样的对照证据，不能作为当前 Kotlin 版本的验收结果。当前规则见 [性能手册](perf-playbook.md)，完整 RN 诊断见 [2026-08-15 记录](performance-diagnosis-2026-08-15.md)。

## 既有采样

- RN 真机 app 每帧 CPU：版块列表 2.52ms、主题详情 2.16ms。
- RN 真机现代 FrameTimeline janky：慢拖 0.1%、快甩 2.7%；分场景 p50 9.3–9.5ms、p95 10.1–11.0ms，无 >33ms 帧。
- 原生重写票 56 的低峰/高峰为 383/183（67.7%/32.3%），两个模式的 present2present 均约 8.32ms；说明均匀送显间隔本身不能排除队列深度振荡。
- 旧 RN 的 debug/release 曾呈现一致双峰，排除了当次问题由 JS dev 模式导致的假设；这不是当前 Compose 构建的结论。
- 小米设备曾出现 timestats 反复 enable/clear 后不再输出 layer 的故障。该设备在该次调查中的状态不代表重启后或其他设备也处于同一状态；出现该症状时遵循手册 T2。

RN 基线来源为 `.scratch/perf-2026-08/device-report.md`；原生采样来源为 `.scratch/native-rewrite/` 对应票及 [完整诊断](performance-diagnosis-2026-08-15.md)。

## RN 触摸模式备忘

**P1 — 历史 RN 触摸分发被父边界裁剪**(出处:pager「过程中踩的新坑」;触摸「排查线索」)

症状指纹:**点击/手势识别一切正常,唯独容器里的原生纵向滚动一个 move 都收不到、无过滚辉光**。
机制:RN(Fabric)Android 有两套命中测试——JS 侧的 `TouchTargetHelper`(Pressable、RNGH 走它,支持 overflow/transform),
和 Android 原生 `dispatchTouchEvent`(ScrollView 这类原生手势组件走它,**按子 view 的布局边界裁剪**,transform 会被逆变换回去再比对)。
子 view 布局在父边界外、靠 transform 拉回屏内时,前者通过、后者裁掉。

2026-08-19 在 SwipePager 重构里踩实:轨道 `absoluteFill`(一屏宽),面板 `left=(页号-1)*屏宽` 落在边界外、transform 平移回屏 → taps 全通、滚动全灭。
修法:轨道显式 `width = count * 屏宽`,让所有面板落在布局边界内。

这段机制来自 RN，不直接作为 Compose 缺陷的判定依据。当前 pager、轮播或抽屉若出现类似症状，应结合实际布局、命中区域和手势消费逐项复现验证。

另有一条**未定位**的相关缺陷(不进原生验收,只作对照):RN 版偶发「整窗口触摸完全失灵,画面正常,force-stop 才恢复」——
与 P1 的区别是**点击也一起死**,不完全吻合;无确定复现,`logcat` 无异常无 ANR(出处:触摸)。
