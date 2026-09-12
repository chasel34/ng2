package com.chasel.ng2n.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

const val PREFETCH_SCREENS: Float = 4f

const val MIN_ITEMS_AHEAD: Int = 6

const val FLING_CONTENT_WAIT_MS: Long = 80L

const val MAX_FLING_RESUMES: Int = 3

private const val SETTLED_VELOCITY = 1f

fun shouldLoadNextPage(
  lastVisibleIndex: Int,
  totalItemsCount: Int,
  averageItemSize: Int,
  viewportSize: Int,
  firstVisibleIndex: Int = 1,
  screensAhead: Float = PREFETCH_SCREENS,
): Boolean {
  if (totalItemsCount <= 0 || lastVisibleIndex < 0) return false
  val itemsAhead = totalItemsCount - 1 - lastVisibleIndex
  if (itemsAhead <= MIN_ITEMS_AHEAD) return true
  if (averageItemSize <= 0 || viewportSize <= 0) return false
  if (firstVisibleIndex <= 0) return false
  return itemsAhead.toFloat() * averageItemSize < viewportSize * screensAhead
}

fun LazyListLayoutInfo.averageVisibleItemSize(): Int {
  val items = visibleItemsInfo
  if (items.isEmpty()) return 0
  val first = items.first()
  val last = items.last()
  val span = last.offset + last.size - first.offset
  return if (span > 0) span / items.size else 0
}

fun LazyListLayoutInfo.viewportSize(): Int = (viewportEndOffset - viewportStartOffset).coerceAtLeast(0)

@Composable
fun rememberShouldLoadNextPage(listState: LazyListState, itemCount: Int): State<Boolean> =
  remember(listState, itemCount) {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
      shouldLoadNextPage(
        lastVisibleIndex = last.index,
        totalItemsCount = info.totalItemsCount,
        averageItemSize = info.averageVisibleItemSize(),
        viewportSize = info.viewportSize(),
        firstVisibleIndex = info.visibleItemsInfo.first().index,
      )
    }
  }

@Composable
fun rememberPagedFlingBehavior(
  listState: LazyListState,
  moreContentComing: () -> Boolean,
): FlingBehavior {
  val delegate = ScrollableDefaults.flingBehavior()
  val pending = rememberUpdatedState(moreContentComing)
  return remember(delegate, listState) {
    PagedFlingBehavior(
      delegate = delegate,
      moreContentComing = { pending.value() },
      canScrollForward = { listState.canScrollForward },
    )
  }
}

enum class FlingHandoff {
  FINISHED,

  YIELD,

  HOLD,
}

fun flingHandoff(
  velocityLeft: Float,
  canScrollForward: Boolean,
  moreContentComing: Boolean,
  resumes: Int,
): FlingHandoff = when {
  abs(velocityLeft) <= SETTLED_VELOCITY -> FlingHandoff.FINISHED
  canScrollForward -> FlingHandoff.YIELD
  !moreContentComing -> FlingHandoff.YIELD
  resumes >= MAX_FLING_RESUMES -> FlingHandoff.FINISHED
  else -> FlingHandoff.HOLD
}

internal class PagedFlingBehavior(
  private val delegate: FlingBehavior,
  private val moreContentComing: () -> Boolean,
  private val canScrollForward: () -> Boolean,
) : FlingBehavior {

  override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
    val scope = this
    var velocity = initialVelocity
    var resumes = 0
    while (true) {
      val left = with(delegate) { scope.performFling(velocity) }
      when (flingHandoff(left, canScrollForward(), moreContentComing(), resumes)) {
        FlingHandoff.FINISHED -> return 0f
        FlingHandoff.YIELD -> return left
        FlingHandoff.HOLD -> Unit
      }
      resumes++
      val arrived = withTimeoutOrNull(FLING_CONTENT_WAIT_MS) {
        snapshotFlow { canScrollForward() }.first { it }
      } != null
      if (!arrived) return 0f
      velocity = left
    }
  }
}

const val PLACEHOLDER_SCREENS: Float = 2f

const val DEFAULT_PLACEHOLDER_ROWS: Int = 12

const val MIN_PLACEHOLDER_ROWS: Int = 6

const val MAX_PLACEHOLDER_ROWS: Int = 24

val DEFAULT_PLACEHOLDER_HEIGHT: Dp = 76.dp

fun tailPlaceholderCount(
  averageItemSize: Int,
  viewportSize: Int,
  screens: Float = PLACEHOLDER_SCREENS,
): Int {
  if (averageItemSize <= 0 || viewportSize <= 0) return DEFAULT_PLACEHOLDER_ROWS
  val rows = ceil(viewportSize * screens / averageItemSize).toInt()
  return rows.coerceIn(MIN_PLACEHOLDER_ROWS, MAX_PLACEHOLDER_ROWS)
}

@Immutable
data class TailPlaceholders(val count: Int, val rowHeight: Dp) {
  companion object {
    val None = TailPlaceholders(count = 0, rowHeight = DEFAULT_PLACEHOLDER_HEIGHT)
  }
}

@Composable
fun rememberTailPlaceholders(listState: LazyListState, loading: Boolean): TailPlaceholders {
  val density = LocalDensity.current
  return remember(listState, loading, density) {
    if (!loading) return@remember TailPlaceholders.None
    // 固定本次加载的占位尺寸，避免订阅滚动布局导致逐帧重组和项数抖动。
    Snapshot.withoutReadObservation {
      val info = listState.layoutInfo
      val average = info.averageVisibleItemSize()
      TailPlaceholders(
        count = tailPlaceholderCount(average, info.viewportSize()),
        rowHeight = if (average > 0) with(density) { average.toDp() } else DEFAULT_PLACEHOLDER_HEIGHT,
      )
    }
  }
}

private const val PLACEHOLDER_KEY = "ng2n:paged-placeholder:"

fun LazyListScope.tailPlaceholders(placeholders: TailPlaceholders) {
  if (placeholders.count <= 0) return
  items(
    count = placeholders.count,
    key = { index -> PLACEHOLDER_KEY + index },
    contentType = { "paged-placeholder" },
  ) { index -> PagedRowPlaceholder(index = index, height = placeholders.rowHeight) }
}

private val PLACEHOLDER_TITLE_WIDTHS = listOf(0.92f, 0.68f, 0.84f, 0.55f, 0.76f)

@Composable
private fun PagedRowPlaceholder(index: Int, height: Dp) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .height(height)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = Spacing.row, bottom = Spacing.md)
      .padding(horizontal = Spacing.lg),
  ) {
    Box(
      Modifier
        .fillMaxWidth(PLACEHOLDER_TITLE_WIDTHS[index % PLACEHOLDER_TITLE_WIDTHS.size])
        .height(13.dp)
        .clip(RoundedCornerShape(6.5.dp))
        .background(colors.quote),
    )
    Spacer(Modifier.height(12.dp))
    Box(
      Modifier
        .fillMaxWidth(0.34f)
        .height(10.dp)
        .clip(RoundedCornerShape(5.dp))
        .background(colors.quote),
    )
  }
}
