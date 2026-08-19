# 翻页手感不如原生客户端（松手断层 + 中段冻结）

Status: resolved（2026-08-19 修复并真机逐帧验证，速度曲线与 anzong 同形）

## 诊断（cv2.phaseCorrelate 逐帧位移，两 app 同手势对拍）

anzong（ViewPager2）：松手后速度从手指速度**连续起步**，指数衰减 358ms，无断点。
我们（持有权改造后的第一版）：

1. **松手瞬间 ~35ms 冻结 + 一帧 140–165px 瞬移**——`runOnJS(commit)` 发在
   onEnd，React 重渲染 + Fabric mount 占住 UI 线程 2–4 帧；`withTiming` 按时间
   驱动，掉帧后直接跳到当前时刻的位置「追帧」。这是体感差距的主项。
2. **固定 260ms 定时曲线不吃松手速度**——轻拖和猛甩同一条曲线，速度不连续。

## 修复

- `withTiming` → `withSpring`（velocity 接住 `event.velocityX`，略过阻尼不回弹，
  尾巴 ~350ms 与 ViewPager2 衰减同形）。
- **commit 推迟到弹簧停稳**才发（`pendingCommit` 共享值）；半路被下一次按下
  接管时当场补发——连滑要的下下页面板不会等太久，且 mount 卡顿落在跟手阶段
  （位置被手指钉着，不会瞬移）。
- 消灭 commit 那一拍的重渲染面积：
  - 详情页 context 按 `TopicDetail` 对象做 WeakMap 身份缓存（同一份数据永远同一个
    context 引用），预览→激活楼层卡零重绘；渲染器统一成一份，激活不换 renderItem。
  - 首页行数组按分类 id 缓存、不挂 activeIndex，commit 后可见列表 data 引用不变。

## 验证（2026-08-19，pudding 120Hz）

- 首页/帖子单滑速度曲线：松手 -51.8→-45 连续衰减至 -2，全程单调,仅松手 1 个
  丢帧（anzong 同级别）；无冻结、无瞬移。
- 功能回归：单滑落页正确、快速连滑 +2（帖子 2→4、首页 网事杂谈→特约专区）、
  两屏纵向滚动正常、接管补发 commit 路径正确。

## 结论沉淀

「原生 pager 才顺滑」的真正机制是**翻页瞬间没有 React 工作落在 UI 线程上**。
任何承载 RN 子树的 pager（react-native-pager-view、@expo/ui pager 同理）都要
面对同一笔 mount 账——解法不是换容器，而是把 React 工作挪出动画窗口 +
用速度连续的物理动画。
