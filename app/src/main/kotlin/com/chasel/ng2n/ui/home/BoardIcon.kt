package com.chasel.ng2n.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.di.ImageModule
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Typo
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 版块图标 —— 直译 RN 侧 `src/ui/board-icon.tsx`。
 *
 * 来源规则(与 RN 版逐字一致):
 * - 服务端在 `other.forum_icon_list` 里**登记过图标**的版块才有地址
 *   (票 07 的 `BoardTree.kt` 已经按 id 清单过滤过 —— 清单外的 id 请求图标必 404,
 *   2026-08-07 抽样 14 个版块实测),没有就直接走占位;
 * - 远程图加载失败也回落到占位;
 * - 占位是设计稿那个「斜纹圆底 + 首字」。
 *
 * 缓存走 memory + disk(RN 侧 `cachePolicy="memory-disk"`):32 见方的小图,
 * 一屏三十来个、切 tab 来回换,只用磁盘的话每次上屏都要重新读盘 + 解码;
 * 解码后一张才 4KB 上下,进内存很划算。
 */
private val ICON_SIZE = 32.dp

/** 占位底纹的条纹周期(设计稿 `repeating-linear-gradient` 的 6px,垂直于条纹量)。 */
private const val STRIPE_PERIOD = 6f

/** 条纹转了 45°,水平方向的间距要放大 √2 才等于设计稿那个垂直周期。 */
private val STRIPE_STEP = STRIPE_PERIOD * sqrt(2f)

@Composable
fun BoardIcon(board: Board, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  // 记的是「哪个版块的图挂了」而不是一个布尔:列表回收时同一个组件会换到别的版块上,
  // 布尔会把上一个版块的失败带过去,让本来有图的格子也画成占位
  var failedId by remember { mutableStateOf<Long?>(null) }

  val url = board.iconUrl
  if (url == null || failedId == board.id) {
    BoardIconPlaceholder(board.name, modifier)
    return
  }

  AsyncImage(
    model = ImageRequest.Builder(context)
      .data(url)
      .memoryCachePolicy(CachePolicy.ENABLED)
      .diskCachePolicy(CachePolicy.ENABLED)
      .crossfade(ImageModule.CROSSFADE_MS)
      .build(),
    contentDescription = null,
    contentScale = ContentScale.Fit,
    onError = { failedId = board.id },
    modifier = modifier.size(ICON_SIZE).clip(CircleShape),
  )
}

/**
 * 「斜纹圆底 + 首字」。斜纹用几条旋转 45° 的细线铺出来 ——
 * 原生这边直接画在 Canvas 上,不像 RN 那样得手搓十几个 View。
 */
@Composable
private fun BoardIconPlaceholder(name: String, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = modifier
      .size(ICON_SIZE)
      .clip(CircleShape)
      .background(colors.surface2)
      .drawBehind {
        val side = size.width
        val step = STRIPE_STEP * density
        val count = ceil(side * 2 / step).toInt()
        val stroke = 1f * density
        for (index in 0 until count) {
          // 线绕自身中心转 45°:两端各多铺半个图标宽,免得露出空角
          val x = index * step - side / 2f
          drawLine(
            color = colors.surface,
            start = Offset(x, side),
            end = Offset(x + side, 0f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt,
          )
        }
      }
      .border(1.dp, colors.accent, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initialOf(name),
      style = TextStyle(
        fontSize = Typo.initial.size,
        lineHeight = Typo.initial.lineHeight,
        fontWeight = FontWeight.Bold,
        color = colors.fg2,
      ),
    )
  }
}

/**
 * 取名字的首字,用于版块图标占位与分组角标 —— 直译 RN 侧 `ui/initial.ts`。
 *
 * 按**码点**而不是 UTF-16 码元切:名字里可能有 emoji 或生僻字,
 * 按码元切会劈出半个代理对,渲染成豆腐块。
 */
fun initialOf(name: String): String {
  val trimmed = name.trim()
  if (trimmed.isEmpty()) return "#"
  val codePoint = trimmed.codePointAt(0)
  return String(Character.toChars(codePoint))
}
