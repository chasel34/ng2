package com.chasel.ng2n.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * 列表用的下拉刷新状态 —— 行为与 material3 的默认实现一致,**只把「什么都没下拉时
 * 还要占一帧动画」这条去掉**(票 57)。
 *
 * ## 为什么要自己实现这个接口
 *
 * [PullToRefreshBox] 的 nested-scroll 节点(`PullToRefreshModifierNode`)挂在每一个
 * 列表的 fling 路径上,而 material3 1.4 的 `onPreFling` 是这么写的:
 *
 * ```kotlin
 * override suspend fun onPreFling(available: Velocity): Velocity =
 *   Velocity(0f, onRelease(available.y))          // onRelease 里无条件 await animateToHidden()
 * ```
 *
 * `dispatchPreFling` 挡在 `ScrollingLogic.onScrollStopped` 里 `doFlingAnimation()`
 * 的**前面**(`Scrollable.kt`:先 `nestedScrollDispatcher.dispatchPreFling(velocity)`,
 * 拿到结果才开 fling 动画)。于是每一次松手都会先等 `Animatable.animateTo(0f)`:
 *
 * 1. 哪怕起点终点都是 0、时长算出来是 0,`animateTo` 也要先 `withFrameNanos` 挂一帧
 *    ——120Hz 上就是每次 fling 白白晚 8.3ms 起步;
 * 2. 更要命的是它跑在 `Animatable` 的 `MutatorMutex` 上,而同一个 state 的
 *    `snapTo` 被 `onPostScroll` **每一个滚动帧**都 `coroutineScope.launch` 一发
 *    (那段代码不看方向,向上滚一样发)。排在后面的 `snapTo` 一旦落在
 *    `animateTo` 拿到 mutex 之后,就会以 `MutationInterruptedException`
 *    (一种 `CancellationException`)把它掐掉;`Animatable.runAnimation` 原样往外抛,
 *    经 `onRelease` → `onPreFling` → `dispatchPreFling` 冒到 `onScrollStopped`,
 *    整个 fling 协程当场取消 —— **列表这一把根本不 fling,松手即停,而且没有任何
 *    日志、不算 jank、不掉帧**,正是票 57 现场的形态。
 *
 * 材料库把 `state` 开成了公开参数(文档里明写 "A custom state implementation can be
 * initialized like this"),所以不必 fork 整个 modifier:只要这两个挂起函数在
 * 「本来就归零」时同步返回,上面两条就都不成立了。指示器该动的时候照样动
 * ——真下拉过([distanceFraction] 非 0)或动画正在跑时,走的还是原来的动画。
 */
@Composable
fun rememberListPullToRefreshState(): PullToRefreshState = remember { ListPullToRefreshState() }

@Stable
internal class ListPullToRefreshState : PullToRefreshState {

  private val anim = Animatable(0f)

  override val distanceFraction: Float get() = anim.value

  override val isAnimating: Boolean get() = anim.isRunning

  override suspend fun animateToThreshold() {
    anim.animateTo(1f)
  }

  override suspend fun animateToHidden() {
    if (!pullToRefreshNeedsHide(anim.value, anim.isRunning)) return
    anim.animateTo(0f)
  }

  override suspend fun snapTo(targetValue: Float) {
    if (!pullToRefreshNeedsSnap(anim.value, targetValue, anim.isRunning)) return
    anim.snapTo(targetValue)
  }
}

/**
 * 「收回指示器」这一下要不要真跑动画。
 *
 * 已经在 0 且没有动画在跑 = 没什么可收的,直接返回,别让 `onPreFling` 占帧、
 * 也别去碰 `MutatorMutex`。
 */
fun pullToRefreshNeedsHide(distanceFraction: Float, animating: Boolean): Boolean =
  distanceFraction != 0f || animating

/**
 * 这一发 `snapTo` 要不要真的落到 `Animatable` 上。
 *
 * 值没变且没有动画在跑就是空操作 —— 滚动中每帧一发的那种 `snapTo(0f)` 全落在这里。
 * 有动画在跑时仍要 snap:`Animatable.snapTo` 顺带把动画停掉,这个副作用不能丢。
 */
fun pullToRefreshNeedsSnap(current: Float, target: Float, animating: Boolean): Boolean =
  current != target || animating

// ---------------------------------------------------------------- 手势方向门(票 62)

/** 一把手势对下拉刷新的裁决。 */
enum class PullGate {
  /** 还没越过 slop,继续看。 */
  UNDECIDED,

  /** 开场就向下:这把允许下拉(顶部的真下拉、或从中段一路拖到顶的长下拉)。 */
  ALLOW,

  /** 开场向上 = 用户在**向前滚列表**:这把从头到尾不许下拉。 */
  BLOCK,
}

/**
 * 按「首个越过 slop 的纵向方向」给整把手势定性。
 *
 * 为什么需要它(票 62,真机日志实锤):连续慢滑的笔画常以一小段**向下回勾**收尾。
 * material3 的 `pullToRefresh` 只看「顶部之外的向下余量」,不看这把手势是怎么开场的 ——
 * 回勾先把列表推回顶部,余下的位移灌进 `distancePulled`,松手超阈值就**静默触发刷新**;
 * 而我们的分页刷新按设计会把已加载页截回第 1 页并把锚点拽回顶部。两者叠加,
 * 用户「划着划着就滑不动了」:每一把带回勾的滑动都在把自己弹回顶部。
 */
fun pullGateDecision(dy: Float, slop: Float): PullGate = when {
  dy < -slop -> PullGate.BLOCK
  dy > slop -> PullGate.ALLOW
  else -> PullGate.UNDECIDED
}

/**
 * 列表页统一的下拉刷新容器:[PullToRefreshBox] 的形状 + 两条修正 ——
 *
 * 1. [rememberListPullToRefreshState](票 57:松手不白等一帧、滚动帧不碰 MutatorMutex);
 * 2. **方向门**(票 62):以向上滚动开场的手势,整把禁用下拉。观察在 `Initial` pass,
 *    不消费任何事件;每把手势按下时重新放行,所以状态永远不会卡死在禁用档。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListPullToRefreshBox(
  isRefreshing: Boolean,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  val state = rememberListPullToRefreshState()
  var armed by remember { mutableStateOf(true) }
  val slop = LocalViewConfiguration.current.touchSlop
  Box(
    modifier
      .pointerInput(slop) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
          armed = true
          var gate = PullGate.UNDECIDED
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (gate == PullGate.UNDECIDED) {
              val change = event.changes.firstOrNull { it.id == down.id } ?: continue
              gate = pullGateDecision(change.position.y - down.position.y, slop)
              if (gate == PullGate.BLOCK) armed = false
            }
          }
        }
      }
      .pullToRefresh(
        isRefreshing = isRefreshing,
        state = state,
        enabled = enabled && armed,
        onRefresh = onRefresh,
      ),
  ) {
    content()
    PullToRefreshDefaults.Indicator(
      modifier = Modifier.align(Alignment.TopCenter),
      isRefreshing = isRefreshing,
      state = state,
    )
  }
}
