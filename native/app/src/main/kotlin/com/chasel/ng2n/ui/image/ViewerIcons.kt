package com.chasel.ng2n.ui.image

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 查看器顶栏的四个图标,直接用 Canvas 画。
 *
 * RN 版走的是打进包里的 Material Icons OTF(84 字形,331KB)+ `<Text>` 渲染,
 * 那是 RN 侧「等字体加载完才放行首屏」那套方案的一部分,原生这边没有理由继承。
 * 票 17 会把完整图标体系(vector drawable)铺开;票 12 只需要这四个,
 * 手画比先引一套资源省事,也不给票 17 留下要清理的半套东西。
 */
private const val ICON_STROKE_RATIO = 0.085f

@Composable
fun BackIcon(tint: Color, size: Dp = 24.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = Stroke(width = w * ICON_STROKE_RATIO, cap = StrokeCap.Round)
    // 横轴 + 左端两撇
    drawLine(tint, Offset(w * 0.20f, w * 0.5f), Offset(w * 0.82f, w * 0.5f), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.20f, w * 0.5f), Offset(w * 0.46f, w * 0.24f), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.20f, w * 0.5f), Offset(w * 0.46f, w * 0.76f), stroke.width, StrokeCap.Round)
  }
}

@Composable
fun SaveIcon(tint: Color, size: Dp = 23.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val width = w * ICON_STROKE_RATIO
    // 竖杆 + 下箭头 + 底托盘
    drawLine(tint, Offset(w * 0.5f, w * 0.16f), Offset(w * 0.5f, w * 0.62f), width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.29f, w * 0.42f), Offset(w * 0.5f, w * 0.63f), width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.71f, w * 0.42f), Offset(w * 0.5f, w * 0.63f), width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.20f, w * 0.82f), Offset(w * 0.80f, w * 0.82f), width, StrokeCap.Round)
  }
}

@Composable
fun ShareIcon(tint: Color, size: Dp = 23.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val width = w * ICON_STROKE_RATIO
    val r = w * 0.10f
    val a = Offset(w * 0.74f, w * 0.20f)
    val b = Offset(w * 0.26f, w * 0.50f)
    val c = Offset(w * 0.74f, w * 0.80f)
    drawLine(tint, a, b, width)
    drawLine(tint, b, c, width)
    drawCircle(tint, r, a)
    drawCircle(tint, r, b)
    drawCircle(tint, r, c)
  }
}

@Composable
fun MoreIcon(tint: Color, size: Dp = 22.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val r = w * 0.085f
    drawCircle(tint, r, Offset(w * 0.5f, w * 0.22f))
    drawCircle(tint, r, Offset(w * 0.5f, w * 0.50f))
    drawCircle(tint, r, Offset(w * 0.5f, w * 0.78f))
  }
}
