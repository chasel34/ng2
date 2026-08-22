package com.chasel.ng2n.ui.topic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主题三屏要的图标,沿用票 12 `ui/image/ViewerIcons.kt` 与票 11 `ui/bbcode/BBCodeIcons.kt`
 * 立下的做法:直接 Canvas 画,不引图标字体也不引 vector 资源。
 *
 * RN 版走的是打进包里的 Material Icons OTF(84 字形,331KB)+ `<Text>` 渲染,
 * 那是 RN 侧「根布局等字体加载完才放行首屏」那套方案的一部分,原生这边没有理由继承
 * (`research/inventory.md` §6)。完整图标体系(vector drawable)仍归**票 17**;
 * 那时把三处手画图标合并成一套即可。
 */
private const val STROKE = 0.085f

private fun DrawScope.line(tint: Color, from: Offset, to: Offset, w: Float, ratio: Float = STROKE) {
  drawLine(tint, from, to, w * ratio, StrokeCap.Round)
}

@Composable
fun BackArrowIcon(tint: Color, size: Dp = 24.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  line(tint, Offset(w * 0.20f, w * 0.5f), Offset(w * 0.82f, w * 0.5f), w)
  line(tint, Offset(w * 0.20f, w * 0.5f), Offset(w * 0.46f, w * 0.24f), w)
  line(tint, Offset(w * 0.20f, w * 0.5f), Offset(w * 0.46f, w * 0.76f), w)
}

/** 「用网页版打开」(地球)。 */
@Composable
fun GlobeIcon(tint: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  val stroke = w * STROKE
  drawCircle(tint, w * 0.38f, Offset(w * 0.5f, w * 0.5f), style = Stroke(stroke))
  drawOval(
    color = tint,
    topLeft = Offset(w * 0.32f, w * 0.12f),
    size = Size(w * 0.36f, w * 0.76f),
    style = Stroke(stroke),
  )
  line(tint, Offset(w * 0.14f, w * 0.5f), Offset(w * 0.86f, w * 0.5f), w)
}

/** 顶栏「更多」。 */
@Composable
fun OverflowIcon(tint: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  val r = w * 0.085f
  drawCircle(tint, r, Offset(w * 0.5f, w * 0.22f))
  drawCircle(tint, r, Offset(w * 0.5f, w * 0.50f))
  drawCircle(tint, r, Offset(w * 0.5f, w * 0.78f))
}

/** 过滤条(漏斗)。 */
@Composable
fun FilterIcon(tint: Color, size: Dp = 17.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  line(tint, Offset(w * 0.12f, w * 0.22f), Offset(w * 0.88f, w * 0.22f), w)
  line(tint, Offset(w * 0.12f, w * 0.22f), Offset(w * 0.44f, w * 0.56f), w)
  line(tint, Offset(w * 0.88f, w * 0.22f), Offset(w * 0.56f, w * 0.56f), w)
  line(tint, Offset(w * 0.44f, w * 0.56f), Offset(w * 0.44f, w * 0.84f), w)
  line(tint, Offset(w * 0.56f, w * 0.56f), Offset(w * 0.56f, w * 0.72f), w)
  line(tint, Offset(w * 0.44f, w * 0.84f), Offset(w * 0.56f, w * 0.72f), w)
}

@Composable
fun CloseIcon(tint: Color, size: Dp = 17.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  line(tint, Offset(w * 0.24f, w * 0.24f), Offset(w * 0.76f, w * 0.76f), w)
  line(tint, Offset(w * 0.76f, w * 0.24f), Offset(w * 0.24f, w * 0.76f), w)
}

/** 网页数据源提示条(i)。 */
@Composable
fun InfoIcon(tint: Color, size: Dp = 19.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  drawCircle(tint, w * 0.40f, Offset(w * 0.5f, w * 0.5f), style = Stroke(w * STROKE))
  drawCircle(tint, w * 0.055f, Offset(w * 0.5f, w * 0.30f))
  line(tint, Offset(w * 0.5f, w * 0.44f), Offset(w * 0.5f, w * 0.72f), w)
}

/** 离线缓存提示条(断线的云)。 */
@Composable
fun CloudOffIcon(tint: Color, size: Dp = 19.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  val stroke = w * STROKE
  drawCircle(tint, w * 0.20f, Offset(w * 0.40f, w * 0.48f), style = Stroke(stroke))
  drawCircle(tint, w * 0.15f, Offset(w * 0.66f, w * 0.56f), style = Stroke(stroke))
  line(tint, Offset(w * 0.18f, w * 0.86f), Offset(w * 0.86f, w * 0.14f), w)
}

/** 整帖缓存进度条(下载)。 */
@Composable
fun DownloadIcon(tint: Color, size: Dp = 19.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  line(tint, Offset(w * 0.5f, w * 0.16f), Offset(w * 0.5f, w * 0.62f), w)
  line(tint, Offset(w * 0.29f, w * 0.42f), Offset(w * 0.5f, w * 0.63f), w)
  line(tint, Offset(w * 0.71f, w * 0.42f), Offset(w * 0.5f, w * 0.63f), w)
  line(tint, Offset(w * 0.20f, w * 0.84f), Offset(w * 0.80f, w * 0.84f), w)
}

/** 「上次读到」浮条(书签)。 */
@Composable
fun BookmarkIcon(tint: Color, size: Dp = 19.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  line(tint, Offset(w * 0.28f, w * 0.14f), Offset(w * 0.72f, w * 0.14f), w)
  line(tint, Offset(w * 0.28f, w * 0.14f), Offset(w * 0.28f, w * 0.86f), w)
  line(tint, Offset(w * 0.72f, w * 0.14f), Offset(w * 0.72f, w * 0.86f), w)
  line(tint, Offset(w * 0.28f, w * 0.86f), Offset(w * 0.5f, w * 0.62f), w)
  line(tint, Offset(w * 0.72f, w * 0.86f), Offset(w * 0.5f, w * 0.62f), w)
}

@Composable
fun ThumbUpIcon(tint: Color, size: Dp = 19.dp) = Canvas(Modifier.size(size)) {
  drawThumb(tint, this.size.width, up = true)
}

@Composable
fun ThumbDownIcon(tint: Color, size: Dp = 19.dp) = Canvas(Modifier.size(size)) {
  drawThumb(tint, this.size.width, up = false)
}

private fun DrawScope.drawThumb(tint: Color, w: Float, up: Boolean) {
  val stroke = w * STROKE
  val flip: DrawScope.(DrawScope.() -> Unit) -> Unit = { body ->
    if (up) body() else rotate(180f) { body() }
  }
  flip {
    // 手掌
    drawRect(
      color = tint,
      topLeft = Offset(w * 0.36f, w * 0.40f),
      size = Size(w * 0.48f, w * 0.42f),
      style = Stroke(stroke),
    )
    // 大拇指
    line(tint, Offset(w * 0.52f, w * 0.40f), Offset(w * 0.52f, w * 0.18f), w)
    line(tint, Offset(w * 0.52f, w * 0.18f), Offset(w * 0.68f, w * 0.30f), w)
    // 袖口
    drawRect(
      color = tint,
      topLeft = Offset(w * 0.14f, w * 0.44f),
      size = Size(w * 0.16f, w * 0.38f),
      style = Stroke(stroke),
    )
  }
}

/** 回复(箭头拐弯);本版本是 toast 桩,入口保留。 */
@Composable
fun ReplyIcon(tint: Color, size: Dp = 20.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  line(tint, Offset(w * 0.18f, w * 0.38f), Offset(w * 0.40f, w * 0.20f), w)
  line(tint, Offset(w * 0.18f, w * 0.38f), Offset(w * 0.40f, w * 0.56f), w)
  line(tint, Offset(w * 0.18f, w * 0.38f), Offset(w * 0.62f, w * 0.38f), w)
  line(tint, Offset(w * 0.62f, w * 0.38f), Offset(w * 0.82f, w * 0.58f), w)
  line(tint, Offset(w * 0.82f, w * 0.58f), Offset(w * 0.82f, w * 0.82f), w)
}

/** FAB 的加号(展开时整枚转 45° 变成 ×,设计稿 isArticle 261 行)。 */
@Composable
fun PlusIcon(tint: Color, size: Dp = 27.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  line(tint, Offset(w * 0.5f, w * 0.18f), Offset(w * 0.5f, w * 0.82f), w, 0.075f)
  line(tint, Offset(w * 0.18f, w * 0.5f), Offset(w * 0.82f, w * 0.5f), w, 0.075f)
}

@Composable
fun RefreshIcon(tint: Color, size: Dp = 19.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  drawArc(
    color = tint,
    startAngle = 40f,
    sweepAngle = 280f,
    useCenter = false,
    topLeft = Offset(w * 0.16f, w * 0.16f),
    size = Size(w * 0.68f, w * 0.68f),
    style = Stroke(w * STROKE, cap = StrokeCap.Round),
  )
  line(tint, Offset(w * 0.86f, w * 0.20f), Offset(w * 0.80f, w * 0.44f), w)
  line(tint, Offset(w * 0.86f, w * 0.20f), Offset(w * 0.62f, w * 0.30f), w)
}

/** 被屏蔽的楼层折叠行(禁止)。 */
@Composable
fun BlockIcon(tint: Color, size: Dp = 17.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  drawCircle(tint, w * 0.38f, Offset(w * 0.5f, w * 0.5f), style = Stroke(w * STROKE))
  line(tint, Offset(w * 0.24f, w * 0.76f), Offset(w * 0.76f, w * 0.24f), w)
}

/** 空态(一页纸)。 */
@Composable
fun EmptyArticleIcon(tint: Color, size: Dp = 40.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  val stroke = w * 0.05f
  drawRect(
    color = tint,
    topLeft = Offset(w * 0.20f, w * 0.12f),
    size = Size(w * 0.60f, w * 0.76f),
    style = Stroke(stroke),
  )
  for (row in 0..2) {
    val y = w * (0.32f + row * 0.18f)
    drawLine(tint, Offset(w * 0.32f, y), Offset(w * 0.68f, y), stroke, StrokeCap.Round)
  }
}

/** 跳页(设计稿 low_priority)。 */
@Composable
fun JumpIcon(tint: Color, size: Dp = 15.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  for (row in 0..2) {
    val y = w * (0.18f + row * 0.22f)
    drawLine(tint, Offset(w * 0.12f, y), Offset(w * 0.72f, y), w * 0.09f, StrokeCap.Round)
  }
  line(tint, Offset(w * 0.32f, w * 0.86f), Offset(w * 0.84f, w * 0.86f), w, 0.09f)
  line(tint, Offset(w * 0.84f, w * 0.86f), Offset(w * 0.66f, w * 0.70f), w, 0.09f)
  line(tint, Offset(w * 0.84f, w * 0.86f), Offset(w * 0.66f, w * 1.00f), w, 0.09f)
}

/** 附件折叠条的两枚:有网(相框)/ 计费网络(信号格)。 */
@Composable
fun AttachImageIcon(tint: Color, size: Dp = 18.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  val stroke = w * STROKE
  drawRect(
    color = tint,
    topLeft = Offset(w * 0.14f, w * 0.20f),
    size = Size(w * 0.72f, w * 0.60f),
    style = Stroke(stroke),
  )
  line(tint, Offset(w * 0.22f, w * 0.72f), Offset(w * 0.44f, w * 0.44f), w)
  line(tint, Offset(w * 0.44f, w * 0.44f), Offset(w * 0.62f, w * 0.66f), w)
  drawCircle(tint, w * 0.06f, Offset(w * 0.66f, w * 0.34f))
}

@Composable
fun CellularIcon(tint: Color, size: Dp = 18.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  for (bar in 0..3) {
    val x = w * (0.18f + bar * 0.21f)
    val top = w * (0.72f - bar * 0.16f)
    drawLine(tint, Offset(x, w * 0.82f), Offset(x, top), w * 0.10f, StrokeCap.Round)
  }
}

/** 发帖设备角标(安卓 / iPhone / 通用)。三档都是一个小方框,内部记号不同。 */
@Composable
fun ClientIcon(tint: Color, kind: ClientIconKind, size: Dp = 13.dp) = Canvas(Modifier.size(size)) {
  val w = this.size.width
  val stroke = w * 0.10f
  drawRect(
    color = tint,
    topLeft = Offset(w * 0.28f, w * 0.14f),
    size = Size(w * 0.44f, w * 0.72f),
    style = Stroke(stroke),
  )
  when (kind) {
    // 安卓:两根天线
    ClientIconKind.ANDROID -> {
      line(tint, Offset(w * 0.36f, w * 0.14f), Offset(w * 0.30f, w * 0.02f), w, 0.08f)
      line(tint, Offset(w * 0.64f, w * 0.14f), Offset(w * 0.70f, w * 0.02f), w, 0.08f)
    }
    // iOS:底部一颗 Home 键
    ClientIconKind.IOS -> drawCircle(tint, w * 0.06f, Offset(w * 0.5f, w * 0.72f))
    // 其它:中间一横
    ClientIconKind.OTHER ->
      drawLine(tint, Offset(w * 0.38f, w * 0.5f), Offset(w * 0.62f, w * 0.5f), stroke)
  }
}

enum class ClientIconKind { ANDROID, IOS, OTHER }

/** 单选 / 多选圆点方框(投票只读)。 */
@Composable
fun ChoiceIcon(tint: Color, multiple: Boolean, chosen: Boolean, size: Dp = 17.dp) =
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w * STROKE
    if (multiple) {
      drawRect(
        color = tint,
        topLeft = Offset(w * 0.16f, w * 0.16f),
        size = Size(w * 0.68f, w * 0.68f),
        style = Stroke(stroke),
      )
      if (chosen) {
        line(tint, Offset(w * 0.30f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), w)
        line(tint, Offset(w * 0.44f, w * 0.68f), Offset(w * 0.72f, w * 0.32f), w)
      }
    } else {
      drawCircle(tint, w * 0.34f, Offset(w * 0.5f, w * 0.5f), style = Stroke(stroke))
      if (chosen) drawCircle(tint, w * 0.17f, Offset(w * 0.5f, w * 0.5f))
    }
  }
