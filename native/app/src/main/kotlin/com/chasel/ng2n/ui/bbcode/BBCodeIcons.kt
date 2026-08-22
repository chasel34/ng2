package com.chasel.ng2n.ui.bbcode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 正文渲染器要的几个图标,沿用票 12 `ui/image/ViewerIcons.kt` 立下的做法:直接 Canvas 画。
 *
 * RN 版走的是打进包里的 Material Icons OTF(84 字形,331KB)+ `<Text>` 渲染 —— 那是
 * RN 侧「等字体加载完才放行首屏」那套方案的一部分,原生这边没有理由继承。
 * 票 17 会把完整图标体系(vector drawable)铺开;这里只画渲染器用到的六个。
 */
private const val STROKE_RATIO = 0.085f

/** `[collapse]` 折叠卡的提要图标(一页纸)。 */
@Composable
fun ArticleIcon(tint: Color, size: Dp = 17.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    drawRect(
      color = tint,
      topLeft = Offset(w * 0.20f, w * 0.14f),
      size = Size(w * 0.60f, w * 0.72f),
      style = Stroke(width = stroke),
    )
    for (row in 0..2) {
      val y = w * (0.32f + row * 0.18f)
      drawLine(tint, Offset(w * 0.32f, y), Offset(w * 0.68f, y), stroke, StrokeCap.Round)
    }
  }
}

/** `[lessernuke]` 版规处罚提示的图标(感叹号三角)。 */
@Composable
fun WarningIcon(tint: Color, size: Dp = 17.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    val apex = Offset(w * 0.5f, w * 0.14f)
    val left = Offset(w * 0.10f, w * 0.84f)
    val right = Offset(w * 0.90f, w * 0.84f)
    drawLine(tint, apex, left, stroke, StrokeCap.Round)
    drawLine(tint, apex, right, stroke, StrokeCap.Round)
    drawLine(tint, left, right, stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.5f, w * 0.38f), Offset(w * 0.5f, w * 0.60f), stroke, StrokeCap.Round)
    drawCircle(tint, w * 0.055f, Offset(w * 0.5f, w * 0.72f))
  }
}

/** `[album]` 相册卡的图标(相框里一座山)。 */
@Composable
fun ImageIcon(tint: Color, size: Dp = 17.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    drawRect(
      color = tint,
      topLeft = Offset(w * 0.14f, w * 0.20f),
      size = Size(w * 0.72f, w * 0.60f),
      style = Stroke(width = stroke),
    )
    drawLine(tint, Offset(w * 0.22f, w * 0.72f), Offset(w * 0.44f, w * 0.44f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.44f, w * 0.44f), Offset(w * 0.66f, w * 0.72f), stroke, StrokeCap.Round)
    drawCircle(tint, w * 0.07f, Offset(w * 0.66f, w * 0.35f))
  }
}

/** `[attach]` 附件卡的图标(下载)。 */
@Composable
fun DownloadIcon(tint: Color, size: Dp = 16.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    drawLine(tint, Offset(w * 0.5f, w * 0.16f), Offset(w * 0.5f, w * 0.62f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.29f, w * 0.42f), Offset(w * 0.5f, w * 0.63f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.71f, w * 0.42f), Offset(w * 0.5f, w * 0.63f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.20f, w * 0.82f), Offset(w * 0.80f, w * 0.82f), stroke, StrokeCap.Round)
  }
}

/** `[flash]` 媒体卡的图标(播放三角)。 */
@Composable
fun PlayIcon(tint: Color, size: Dp = 20.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    val a = Offset(w * 0.36f, w * 0.24f)
    val b = Offset(w * 0.76f, w * 0.50f)
    val c = Offset(w * 0.36f, w * 0.76f)
    drawLine(tint, a, b, stroke, StrokeCap.Round)
    drawLine(tint, b, c, stroke, StrokeCap.Round)
    drawLine(tint, c, a, stroke, StrokeCap.Round)
  }
}

/** 「点了会离开本 app」的角标(右上箭头)。 */
@Composable
fun ExternalIcon(tint: Color, size: Dp = 16.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    drawLine(tint, Offset(w * 0.24f, w * 0.76f), Offset(w * 0.76f, w * 0.24f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.42f, w * 0.24f), Offset(w * 0.76f, w * 0.24f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.76f, w * 0.24f), Offset(w * 0.76f, w * 0.58f), stroke, StrokeCap.Round)
  }
}

/** 热门回复区的图标(火苗)。 */
@Composable
fun FireIcon(tint: Color, size: Dp = 18.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    // 外焰:一个上尖下圆的轮廓,用四段线勾出来就够辨识
    drawLine(tint, Offset(w * 0.5f, w * 0.12f), Offset(w * 0.24f, w * 0.52f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.24f, w * 0.52f), Offset(w * 0.36f, w * 0.86f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.36f, w * 0.86f), Offset(w * 0.68f, w * 0.82f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.68f, w * 0.82f), Offset(w * 0.5f, w * 0.12f), stroke, StrokeCap.Round)
    drawCircle(tint, w * 0.10f, Offset(w * 0.48f, w * 0.68f))
  }
}

/** 「查看对话链」入口的图标(一棵倒挂的树)。 */
@Composable
fun ChainIcon(tint: Color, size: Dp = 15.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    val r = w * 0.11f
    drawCircle(tint, r, Offset(w * 0.5f, w * 0.18f), style = Stroke(width = stroke))
    drawCircle(tint, r, Offset(w * 0.22f, w * 0.82f), style = Stroke(width = stroke))
    drawCircle(tint, r, Offset(w * 0.78f, w * 0.82f), style = Stroke(width = stroke))
    drawLine(tint, Offset(w * 0.5f, w * 0.30f), Offset(w * 0.5f, w * 0.52f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.22f, w * 0.52f), Offset(w * 0.78f, w * 0.52f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.22f, w * 0.52f), Offset(w * 0.22f, w * 0.70f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.78f, w * 0.52f), Offset(w * 0.78f, w * 0.70f), stroke, StrokeCap.Round)
  }
}

/** 展开 / 收起的箭头。[expanded] 时朝下,收起时朝右。 */
@Composable
fun ChevronIcon(tint: Color, expanded: Boolean, size: Dp = 20.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    if (expanded) {
      drawLine(tint, Offset(w * 0.28f, w * 0.40f), Offset(w * 0.5f, w * 0.62f), stroke, StrokeCap.Round)
      drawLine(tint, Offset(w * 0.72f, w * 0.40f), Offset(w * 0.5f, w * 0.62f), stroke, StrokeCap.Round)
    } else {
      drawLine(tint, Offset(w * 0.40f, w * 0.28f), Offset(w * 0.62f, w * 0.5f), stroke, StrokeCap.Round)
      drawLine(tint, Offset(w * 0.40f, w * 0.72f), Offset(w * 0.62f, w * 0.5f), stroke, StrokeCap.Round)
    }
  }
}
