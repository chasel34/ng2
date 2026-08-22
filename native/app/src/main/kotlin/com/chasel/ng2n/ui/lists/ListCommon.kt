package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.home.initialOf
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import com.chasel.ng2n.ui.theme.avatarColorFor
import kotlinx.coroutines.delay

/**
 * 票 17a 五组列表屏共用的零件。
 *
 * 屏本身按设计稿分档(`isSimpleList` 历史/缓存、`isSearch`、`isFolders`、`isNotify`),
 * 但副标题条、列表末尾留白、头像占位、每分钟走一格的时钟这几样是同一份,收在这里。
 */

/** 设计稿列表末尾留白 26。 */
val LIST_TAIL_HEIGHT: Dp = 26.dp

/**
 * 设计稿 `listSub`:11/16 内边距、surface2 底、下分隔线的 12 号 meta 字副标题条。
 * [onClick] 非空时整条可点(收藏夹页的「点此换收藏夹」)。
 */
@Composable
fun ListSubtitle(
  text: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(colors.surface2)
      .let { if (onClick == null) it else it.clickable(onClickLabel = text, onClick = onClick) }
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = 11.dp, horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Text(
      text = text,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
      style = TextStyle(
        fontSize = Typo.cardMeta.size,
        lineHeight = Typo.cardMeta.lineHeight,
        color = colors.meta,
      ),
    )
    trailing?.invoke()
  }
}

/** 列表末尾的留白。 */
@Composable
fun ListTail(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(LIST_TAIL_HEIGHT))
}

/**
 * 「纯色圆底 + 名字首字」的头像占位(RN 侧 `ui/avatar.tsx` 的回落形态)。
 *
 * 通知条目与用户搜索结果都用它。底色按 [colorKey] 稳定取一档
 * ([avatarColorFor]),同一个人到处同色。
 */
@Composable
fun InitialAvatar(
  name: String,
  colorKey: String,
  size: Dp,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  shape: Shape = CircleShape,
) {
  Box(
    modifier = modifier.size(size).clip(shape).background(avatarColorFor(colorKey)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initialOf(name),
      style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color.White),
    )
  }
}

/**
 * 每分钟走一格的时钟(秒级 unix 时间戳)。
 *
 * 「N 分钟前」这类相对时间会过期,页面停留时要刷基准 —— RN 侧是 `useMinuteTick`
 * 的 `setInterval`。**只在整页级别订阅一次**,行组件拿它当纯参数:
 * 每行各起一个协程的话,一屏几十行就是几十个定时器。
 */
@Composable
fun rememberMinuteTick(): State<Long> = produceState(initialValue = nowSeconds()) {
  while (true) {
    delay(MINUTE_MS)
    value = nowSeconds()
  }
}

private const val MINUTE_MS = 60_000L

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
