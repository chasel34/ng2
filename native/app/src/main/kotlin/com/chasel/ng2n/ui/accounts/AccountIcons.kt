package com.chasel.ng2n.ui.accounts

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
 * 登录 / 账号管理 / 账号头这三处要用的图标,沿用票 12 的做法:**Canvas 手画**。
 *
 * 理由同 `ui/image/ViewerIcons.kt` —— RN 版走的是打进包里的 Material Icons OTF,
 * 那是 RN 侧「等字体加载完才放行首屏」方案的一部分,原生这边没有理由继承;
 * 票 17 会把完整图标体系(vector drawable)铺开,在那之前不引半套资源。
 */
private const val STROKE_RATIO = 0.085f

@Composable
fun CloseIcon(tint: Color, size: Dp = 24.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    drawLine(tint, Offset(w * 0.24f, w * 0.24f), Offset(w * 0.76f, w * 0.76f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.76f, w * 0.24f), Offset(w * 0.24f, w * 0.76f), stroke, StrokeCap.Round)
  }
}

@Composable
fun RefreshIcon(tint: Color, size: Dp = 22.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE_RATIO
    // 缺口圆 + 箭头尖
    drawArc(
      color = tint,
      startAngle = 40f,
      sweepAngle = 290f,
      useCenter = false,
      topLeft = Offset(w * 0.20f, w * 0.20f),
      size = Size(w * 0.60f, w * 0.60f),
      style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawLine(tint, Offset(w * 0.78f, w * 0.44f), Offset(w * 0.66f, w * 0.30f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.78f, w * 0.44f), Offset(w * 0.90f, w * 0.30f), stroke, StrokeCap.Round)
  }
}

@Composable
fun LockIcon(tint: Color, size: Dp = 16.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * 0.11f
    drawArc(
      color = tint,
      startAngle = 180f,
      sweepAngle = 180f,
      useCenter = false,
      topLeft = Offset(w * 0.28f, w * 0.16f),
      size = Size(w * 0.44f, w * 0.44f),
      style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawRoundRect(
      color = tint,
      topLeft = Offset(w * 0.20f, w * 0.42f),
      size = Size(w * 0.60f, w * 0.44f),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
    )
  }
}

@Composable
fun ChevronIcon(tint: Color, pointsLeft: Boolean, size: Dp = 18.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * 0.12f
    val tip = if (pointsLeft) w * 0.34f else w * 0.66f
    val tail = if (pointsLeft) w * 0.62f else w * 0.38f
    drawLine(tint, Offset(tail, w * 0.22f), Offset(tip, w * 0.5f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(tail, w * 0.78f), Offset(tip, w * 0.5f), stroke, StrokeCap.Round)
  }
}

/** 单选圈:当前账号那一条是实心的。 */
@Composable
fun RadioIcon(tint: Color, checked: Boolean, size: Dp = 22.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    drawCircle(tint, radius = w * 0.36f, style = Stroke(width = w * 0.09f))
    if (checked) drawCircle(tint, radius = w * 0.19f)
  }
}

@Composable
fun PersonAddIcon(tint: Color, size: Dp = 21.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * 0.09f
    drawCircle(tint, radius = w * 0.17f, center = Offset(w * 0.40f, w * 0.32f), style = Stroke(stroke))
    drawArc(
      color = tint,
      startAngle = 200f,
      sweepAngle = 140f,
      useCenter = false,
      topLeft = Offset(w * 0.12f, w * 0.50f),
      size = Size(w * 0.56f, w * 0.48f),
      style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawLine(tint, Offset(w * 0.80f, w * 0.30f), Offset(w * 0.80f, w * 0.58f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.66f, w * 0.44f), Offset(w * 0.94f, w * 0.44f), stroke, StrokeCap.Round)
  }
}

@Composable
fun LogoutIcon(tint: Color, size: Dp = 20.dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * 0.10f
    // 门框(缺右边)+ 向右的箭头
    drawLine(tint, Offset(w * 0.46f, w * 0.14f), Offset(w * 0.14f, w * 0.14f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.14f, w * 0.14f), Offset(w * 0.14f, w * 0.86f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.14f, w * 0.86f), Offset(w * 0.46f, w * 0.86f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.44f, w * 0.50f), Offset(w * 0.88f, w * 0.50f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.72f, w * 0.34f), Offset(w * 0.88f, w * 0.50f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.72f, w * 0.66f), Offset(w * 0.88f, w * 0.50f), stroke, StrokeCap.Round)
  }
}
