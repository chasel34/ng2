# 62 — P0:慢滑回勾误触下拉刷新,分页被截断、用户被反复拽回顶部

**Status:** resolved(方向门修复,已真机复现定位;待复验)

**Severity:** P0(核心浏览路径;用户连续慢滑时「滑不动」,曾被误判为性能问题)

## 现象(用户真机,2026-08-31)

用户在版块列表慢速连续滑动,约 40 秒后「手指滑动不再生效」:录屏 42.6s 起画面完全
静止 30+ 秒,期间触摸事件满速到达 Compose 根(trace `AndroidOwner:onTouch` 40–116/s)
但零重组/零测量/零 overscroll;数分钟后自愈。早前(08-27 前的版本)快甩场景也出现过
同族现象(「还在滚但手没用了」)。

## 定位(scrolldbg 仪器包,logcat tag ng2n-scrolldbg)

23:47:43–51 的 8 秒实录钉死了因果链:

- 列表 `totalItemsCount` 在 39→67→38→54→73 间震荡 —— **数据集每 1–2 秒被整份换掉**;
- 23:47:46.9:`idx=0 off=0 canScrollBackward=false` —— 用户正往下滑却被**瞬移回顶部**;
- 多把手势的 dy 序列形如 `-22,-20,-19,-17,+38,+50,+39` —— 慢滑笔画以**向下回勾**收尾。

链条:回勾先把列表推回顶部,余量灌进 material3 `pullToRefresh` 的 `distancePulled`,
松手越过 80dp 阈值 → **静默触发 onRefresh**(指示器被
`isRefreshing = refreshing && !loadingNextPage` 藏住,全程无视觉反馈)→
`TopicListRepository.refresh` 按设计把已加载页截回第 1 页(ADR-0002:防止整链重打)
→ LazyColumn 锚点失效拽回顶部 → 用户继续滑 → 再回勾 → 再截断……
循环期间反复打 page-1 请求,NGA 随之限流,页面越来越慢,「滑不动」窗口越拖越长。

排除项(逐一验证过):抽屉遮罩命中(卡死时 uiautomator 无遮罩节点)、
PagedFlingBehavior 扣速(80ms×3 硬顶)、主线程阻塞(67s 内仅 1.8s Running)、
EdgeEffect(trace 零 slice)、拉页重试风暴(IO 线程空闲)、m3 无限吃手势
(consumeAvailableOffset 上限为 distancePulled)。

## 修复

`ui/common/ListPullToRefresh.kt` 新增:

- `pullGateDecision(dy, slop)` 纯函数:一把手势按**首个越过 touchSlop 的纵向方向**定性
  (向下=ALLOW、向上=BLOCK、未越过=UNDECIDED);
- `ListPullToRefreshBox`:统一的下拉容器(`rememberListPullToRefreshState` +
  `Modifier.pullToRefresh(enabled = …)`),Initial pass 观察指针(不消费),
  以向上滚动开场的手势**整把禁用下拉**;每把手势按下时重新放行,状态不会卡死。
  顶部的真下拉、以及从中段一路拖到顶的长下拉(开场即向下)不受影响。

八处调用点(版块/收藏/热帖/精华/搜索/他的主题/楼层/过滤词)统一换用
`ListPullToRefreshBox`,并移除各自的 `state = rememberListPullToRefreshState()`。

## 单测

`PullGateTest`(4 例):三档裁决 + 真机日志回勾序列的封禁时序。

## 复验怎么判

1. 复现脚本:版块列表用 `input motionevent` 造「上滑+回勾」单手势,重复 20 把,
   期间 `totalItemsCount` 不得出现截断(67→38 类),位置不得跳回顶部;
2. 回归:列表在顶部时故意下拉过阈值,仍出指示器、仍触发刷新且回到第 1 页;
3. 用户实操:慢速连续滑动 ≥90 秒无「滑不动」。
