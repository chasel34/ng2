package com.chasel.ng2n.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

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
