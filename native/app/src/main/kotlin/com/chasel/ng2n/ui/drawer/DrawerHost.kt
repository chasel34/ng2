package com.chasel.ng2n.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 左侧抽屉 —— **自实现**,不用 M3 的 `ModalNavigationDrawer`。
 *
 * 理由是行为要与 RN 版 1:1(票面):M3 的默认手势从整个左边缘接管、阈值与时长是
 * 它自己那套 Material motion,而本项目的抽屉有三条硬约束:
 *
 * 1. **遮罩与面板共用一个 progress**(设计稿本来是分开的两条 .2s / .22s;RN 侧刻意
 *    合成一条,因为拖动时遮罩必须跟着手指一起深浅,拆开就对不上);
 * 2. **左边缘 22dp 是抽屉的地盘,22dp 之外归首页的横滑 pager** —— 这条让位规则 M3
 *    表达不了(它的 drawerState 没有「边缘宽度」这个概念);
 * 3. 开 220ms / 关 200ms、位移 >12dp 才认、40% 或速度阈值完成 —— 判据在
 *    [DrawerGeometry],与 RN 侧同源。
 *
 * ## 手势怎么抢在 pager 前面
 *
 * 边缘那一条的判定挂在**宿主容器**上并跑 [PointerEventPass.Initial] —— Initial 是
 * **父到子**的方向,父节点先看到事件,认领后 `consume()`,pager(子节点,Main 通道)
 * 就再也收不到这一串。这比「盖一条 22dp 宽的透明 View 在最上面」正确:那样做
 * **点击也会一起被吃掉**,而首页最左一列版块格子正好压在这 22dp 里
 * (RN 侧 `DrawerEdgeHandle` 靠 `onMoveShouldSetPanResponder` 只在**移动**时认领、
 * 点击照样穿透,Initial 通道是等效写法)。
 *
 * ## 面板落在布局边界内
 *
 * 面板 `width = 300dp` 贴左摆,关着时靠 `graphicsLayer` 平移出屏 —— 这是
 * `docs/perf-playbook.md` P1 那条(子 view 布局在父边界外靠 transform 拉回 ⇒
 * 原生滚动全灭)的**反面写法**。
 */
@Stable
class DrawerHostState internal constructor() {

  private val progressState = mutableFloatStateOf(0f)

  /** 0 = 全关,1 = 全开。遮罩与面板共用它。 */
  val progress: Float get() = progressState.floatValue

  /**
   * 「该是开着的」。用它而不是 `progress > 0` 驱动返回键与无障碍:
   * 后者在动画期间每帧都变,会把整棵子树拖进逐帧重组。
   */
  var isOpen: Boolean by mutableStateOf(false)
    private set

  private val animatable = Animatable(0f)
  private var animation: Job? = null

  internal fun snap(value: Float) {
    progressState.floatValue = value
  }

  /** 手指按下并认领时调:掐掉在跑的收尾动画,从当前进度接着走(原生 pager 的手感)。 */
  internal fun beginDrag(): Float {
    animation?.cancel()
    animation = null
    return progress
  }

  internal fun settle(scope: CoroutineScope, open: Boolean) {
    isOpen = open
    animation?.cancel()
    animation = scope.launch {
      animatable.snapTo(progress)
      animatable.animateTo(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(
          durationMillis = if (open) {
            DrawerGeometry.OPEN_DURATION_MS
          } else {
            DrawerGeometry.CLOSE_DURATION_MS
          },
          easing = Motion.easeStandard,
        ),
      ) { snap(value) }
    }
  }

  fun open(scope: CoroutineScope) = settle(scope, true)

  fun close(scope: CoroutineScope) = settle(scope, false)
}

@Composable
fun rememberDrawerHostState(): DrawerHostState = remember { DrawerHostState() }

@Composable
fun DrawerHost(
  state: DrawerHostState,
  drawerContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()

  val widthPx = with(density) { DrawerGeometry.WIDTH_DP.dp.toPx() }
  val edgePx = with(density) { DrawerGeometry.EDGE_WIDTH_DP.dp.toPx() }
  val slopPx = with(density) { DrawerGeometry.GESTURE_SLOP_DP.dp.toPx() }

  // 返回键关闭(RN 侧 `BackHandler.addEventListener('hardwareBackPress')`)。
  // 只在开着时拦,否则连「从首页退出 app」也一起吃掉
  BackHandler(enabled = state.isOpen) { state.close(scope) }

  // 关到底之后整块不再参与组合与命中测试(RN 侧 `pointerEvents='none'` 的对应物)。
  // derivedStateOf:progress 每帧都在变,但这个布尔只在 0↔非 0 时翻面
  val visible by remember(state) { derivedStateOf { state.progress > 0f } }

  Box(modifier.fillMaxSize()) {
    Box(
      Modifier
        .fillMaxSize()
        .drawerDrag(
          state = state,
          scope = scope,
          widthPx = widthPx,
          slopPx = slopPx,
          edgePx = edgePx,
          allowOpen = true,
          allowClose = false,
          pass = PointerEventPass.Initial,
        ),
    ) {
      content()
    }

    if (visible) {
      Box(
        Modifier
          .fillMaxSize()
          // 在 draw 阶段读 progress:每帧只重画,不重组。
          //
          // 票 59:**只画面板右缘之外那一条**。面板不透明又画在遮罩之上,左边那一大块
          // 遮罩每帧都被整个盖掉(开到底时约 74% 面积),纯 overdraw;票 58 裁定第五节
          // 实测抽屉链每帧 GPU 光栅是 tab 链的 4 倍、p95 已贴住 8.333ms 预算,而单帧
          // 撑爆预算会把 SF 推进「多压一档 buffer」的粘滞态。几何见 [drawerScrimLeft]。
          .drawBehind {
            val left = drawerScrimLeft(state.progress, widthPx, size.width)
            drawRect(
              color = colors.scrim,
              topLeft = Offset(left, 0f),
              size = Size(size.width - left, size.height),
              alpha = state.progress,
            )
          }
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "关闭抽屉",
          ) { state.close(scope) }
          .semantics { contentDescription = "关闭抽屉" },
      )
      Box(
        Modifier
          .align(Alignment.CenterStart)
          .width(DrawerGeometry.WIDTH_DP.dp)
          .fillMaxHeight()
          // 在 layer 阶段读 progress:同上,每帧只重放层,不重组
          .graphicsLayer { translationX = -(1f - state.progress) * widthPx }
          .shadow(Elevation.level2)
          // 面板底色**只在这里画一次**。两件事挂在它上面:
          // 1. 票 59 的遮罩裁剪成立的前提是「面板整块不透明」(三套配色的 surface
          //    全是 0xFF…);哪天有人把它改成半透明,遮罩那一刀就要一起撤;
          // 2. `drawerContent` 因此**不该再铺一层满屏底色** —— 那是同一块 900×2712px
          //    的不透明填充画两遍(票 59 顺手削掉了 AppDrawerContent 里的那一层)。
          .background(colors.surface)
          .drawerDrag(
            state = state,
            scope = scope,
            widthPx = widthPx,
            slopPx = slopPx,
            edgePx = null,
            allowOpen = false,
            allowClose = true,
            pass = PointerEventPass.Initial,
          ),
      ) {
        drawerContent()
      }
    }
  }
}

/**
 * 一次抽屉手势的完整生命周期。边缘拉出与面板左划共用这一段 ——
 * 两处各写一份的话阈值迟早走偏(RN 侧就是各写一份,开与关的判据因此不对称)。
 *
 * 两处都跑 [PointerEventPass.Initial]:**认领之前一个事件都不消费**,所以纵向滚动
 * 与条目点击照常走;一旦认领(横向 >12dp 且横纵比过 1.3)就每一发都 `consume()`,
 * 子节点从此收不到。
 *
 * 面板那一处本来挂在 Main 通道上(想着「让子节点先挑」),2026-08-22 模拟器实测
 * 不成立:抽屉里横划一把会**触发落点那一行的点击**(划到「由 URL 读取」就弹出了
 * 那个对话框)。原因是 Main 通道是子 → 父,`clickable` 的
 * `waitForUpOrCancellation` 处理完这一发时父节点还没来得及消费,而它只在
 * 「手指离开边界」时才取消,横向平移到别处并不算离开。Initial 通道没有这个时序问题。
 *
 * @param edgePx 非空表示只接左边缘那一条起手的(首页让位规则);空表示整块都接
 */
private fun Modifier.drawerDrag(
  state: DrawerHostState,
  scope: CoroutineScope,
  widthPx: Float,
  slopPx: Float,
  edgePx: Float?,
  allowOpen: Boolean,
  allowClose: Boolean,
  pass: PointerEventPass,
): Modifier = pointerInput(widthPx, slopPx, edgePx, allowOpen, allowClose, pass) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (edgePx != null && !isInDrawerEdge(down.position.x, edgePx)) return@awaitEachGesture

    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var startProgress = 0f
    var claimed = false
    var settled = false

    while (true) {
      val event = awaitPointerEvent(pass)
      val change = event.changes.firstOrNull { it.id == down.id } ?: break
      // 认领之前别人先动手了(面板里的纵向滚动):这一把不归我们
      if (!claimed && change.isConsumed) break
      tracker.addPosition(change.uptimeMillis, change.position)

      if (!change.pressed) {
        if (claimed) {
          change.consume()
          // 松手那一下带着**最后一个坐标**:注入拖拽(`adb input swipe`)的末段比真手指稀疏,
          // 只认最后一个 move 会把「刚好过 40%」判成没过;真手指抬起时同样带着最终位置
          // (RN 侧 `onPanResponderRelease` 读的 `gesture.dx` 也是含抬手位置的那一个)
          state.snap(drawerProgress(startProgress, change.position.x - down.position.x, widthPx))
          val velocity = tracker.calculateVelocity().x / 1000f
          state.settle(scope, settleDrawerOpen(startProgress, state.progress, velocity))
          settled = true
        }
        break
      }

      val dx = change.position.x - down.position.x
      val dy = change.position.y - down.position.y
      if (!claimed) {
        if (!shouldClaimDrawerDrag(dx, dy, slopPx, allowOpen, allowClose)) continue
        claimed = true
        startProgress = state.beginDrag()
      }
      change.consume()
      state.snap(drawerProgress(startProgress, dx, widthPx))
    }

    // 手势被系统/别人取消,而我们已经跟过手:别把抽屉停在半路
    if (claimed && !settled) state.settle(scope, state.progress >= 0.5f)
  }
}
