package com.chasel.ng2n.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 铺屏用的图标集。
 *
 * **沿用票 12 / 票 11 立下的做法:Canvas 直接画**(`ui/image/ViewerIcons.kt`、
 * `ui/bbcode/BBCodeIcons.kt`)。RN 版走的是打进包里的 Material Icons OTF
 * (84 字形 331KB)+ `<Text>` 渲染,那是 RN 侧「等字体加载完才放行首屏」方案的一部分,
 * 原生这边没有理由继承——多一份字体资源、多一次字形回退风险。
 *
 * 造型是 Material Symbols Outlined 的**几何近似**:同一套 24 格坐标、同一档
 * 线宽比例([STROKE_RATIO]),尺寸由调用方按设计稿逐处标的字号给。
 * 票 11 的注释里写着「票 17 会把完整图标体系(vector drawable)铺开」——
 * 到那时把本文件整体换掉即可,调用点只认 [Ng2nIcon] 这个枚举,不认画法。
 */
enum class Ng2nIcon {
  MENU,
  SEARCH,
  ARROW_BACK,
  ARROW_FORWARD,
  MORE_VERT,
  ADD,
  CLOSE,
  CHEVRON_LEFT,
  CHEVRON_RIGHT,
  STAR,
  PUSH_PIN,
  CAMPAIGN,
  PERSON,
  PERSON_ADD,
  CHAT_BUBBLE,
  WORKSPACE_PREMIUM,
  LIBRARY_ADD,
  BOOKMARK,
  FOLDER_SPECIAL,
  WARNING,
  ARTICLE,
  REPLY,
  CACHED,
  SMS,
  NOTIFICATIONS_ACTIVE,
  SETTINGS,
  INFO,
  FILTER_ALT,
  REFRESH,
  ACCOUNT_TREE,
  LOCAL_FIRE_DEPARTMENT,
  CLOUD_OFF,
  SCIENCE,

  // ---- 票 17a 追加(搜索 / 收藏 / 历史 / 缓存 / 通知)----

  /** 历史页与搜索历史行 */
  HISTORY,
  /** 单条删除(缓存行尾、收藏夹卡片) */
  DELETE,
  /** 整屏清空(历史 / 缓存 / 通知顶栏) */
  DELETE_SWEEP,
  /** 重命名收藏夹 */
  EDIT,
  FOLDER,
  CREATE_NEW_FOLDER,
  /** 副标题条右侧那枚「点此换收藏夹」的箭头 */
  EXPAND_MORE,
  /** 搜索选项的单选圈(空) */
  RADIO_BUTTON_UNCHECKED,
  /** 搜索选项的单选圈(选中) */
  RADIO_BUTTON_CHECKED,
  CHECK_BOX_OUTLINE_BLANK,
  CHECK_BOX,
  /** 缓存行的下载标 */
  DOWNLOAD,
  /** 通知分组「@ 我的」 */
  ALTERNATE_EMAIL,
  /** 通知分组「给我贴条的」 */
  STICKY_NOTE_2,
  /** 通知分组「收到的评价」 */
  THUMB_UP,
}

/** 线宽 = 边长 × 这个比例(与 `BBCodeIcons.kt` 同档,同屏混用不会粗细不一)。 */
private const val STROKE_RATIO = 0.085f

@Composable
fun AppIcon(
  icon: Ng2nIcon,
  tint: Color,
  size: Dp = 22.dp,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier.size(size)) { drawIcon(icon, tint) }
}

/** 画在当前 [DrawScope] 的**正方形**范围里(取 width 当边长)。 */
fun DrawScope.drawIcon(icon: Ng2nIcon, tint: Color) {
  val w = size.width
  val s = w * STROKE_RATIO
  when (icon) {
    Ng2nIcon.MENU -> for (row in 0..2) hLine(tint, s, w, 0.14f, 0.86f, 0.28f + row * 0.22f)

    Ng2nIcon.SEARCH -> {
      drawCircle(tint, w * 0.28f, Offset(w * 0.44f, w * 0.44f), style = Stroke(s))
      line(tint, s, w, 0.64f, 0.64f, 0.86f, 0.86f)
    }

    Ng2nIcon.ARROW_BACK -> {
      hLine(tint, s, w, 0.16f, 0.84f, 0.5f)
      line(tint, s, w, 0.16f, 0.5f, 0.44f, 0.22f)
      line(tint, s, w, 0.16f, 0.5f, 0.44f, 0.78f)
    }

    Ng2nIcon.ARROW_FORWARD -> {
      hLine(tint, s, w, 0.16f, 0.84f, 0.5f)
      line(tint, s, w, 0.84f, 0.5f, 0.56f, 0.22f)
      line(tint, s, w, 0.84f, 0.5f, 0.56f, 0.78f)
    }

    Ng2nIcon.MORE_VERT -> for (row in 0..2) drawCircle(tint, w * 0.085f, Offset(w * 0.5f, w * (0.2f + row * 0.3f)))

    Ng2nIcon.ADD -> {
      hLine(tint, s, w, 0.18f, 0.82f, 0.5f)
      line(tint, s, w, 0.5f, 0.18f, 0.5f, 0.82f)
    }

    Ng2nIcon.CLOSE -> {
      line(tint, s, w, 0.22f, 0.22f, 0.78f, 0.78f)
      line(tint, s, w, 0.78f, 0.22f, 0.22f, 0.78f)
    }

    Ng2nIcon.CHEVRON_LEFT -> {
      line(tint, s, w, 0.62f, 0.20f, 0.36f, 0.5f)
      line(tint, s, w, 0.36f, 0.5f, 0.62f, 0.80f)
    }

    Ng2nIcon.CHEVRON_RIGHT -> {
      line(tint, s, w, 0.38f, 0.20f, 0.64f, 0.5f)
      line(tint, s, w, 0.64f, 0.5f, 0.38f, 0.80f)
    }

    // 五角星。已收藏时调用方换 tint(设计稿没有 Outlined 的 FILL 轴,靠颜色区分)
    Ng2nIcon.STAR -> drawPath(starPath(w), tint, style = Stroke(s, join = StrokeJoin.Round))

    Ng2nIcon.PUSH_PIN -> {
      hLine(tint, s, w, 0.30f, 0.70f, 0.22f)
      line(tint, s, w, 0.38f, 0.22f, 0.34f, 0.56f)
      line(tint, s, w, 0.62f, 0.22f, 0.66f, 0.56f)
      hLine(tint, s, w, 0.26f, 0.74f, 0.56f)
      line(tint, s, w, 0.5f, 0.56f, 0.5f, 0.86f)
    }

    // 喇叭:一个左小右大的梯形 + 两道声波
    Ng2nIcon.CAMPAIGN -> {
      val horn = Path().apply {
        moveTo(w * 0.16f, w * 0.36f)
        lineTo(w * 0.42f, w * 0.36f)
        lineTo(w * 0.62f, w * 0.18f)
        lineTo(w * 0.62f, w * 0.82f)
        lineTo(w * 0.42f, w * 0.64f)
        lineTo(w * 0.16f, w * 0.64f)
        close()
      }
      drawPath(horn, tint, style = Stroke(s, join = StrokeJoin.Round))
      hLine(tint, s, w, 0.72f, 0.86f, 0.5f)
      line(tint, s, w, 0.72f, 0.34f, 0.84f, 0.26f)
      line(tint, s, w, 0.72f, 0.66f, 0.84f, 0.74f)
    }

    Ng2nIcon.PERSON -> {
      drawCircle(tint, w * 0.17f, Offset(w * 0.5f, w * 0.32f), style = Stroke(s))
      drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.20f, w * 0.56f),
        size = Size(w * 0.60f, w * 0.56f),
        style = Stroke(s, cap = StrokeCap.Round),
      )
    }

    Ng2nIcon.PERSON_ADD -> {
      drawCircle(tint, w * 0.15f, Offset(w * 0.40f, w * 0.32f), style = Stroke(s))
      drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.12f, w * 0.56f),
        size = Size(w * 0.56f, w * 0.52f),
        style = Stroke(s, cap = StrokeCap.Round),
      )
      hLine(tint, s, w, 0.66f, 0.94f, 0.30f)
      line(tint, s, w, 0.80f, 0.16f, 0.80f, 0.44f)
    }

    Ng2nIcon.CHAT_BUBBLE -> {
      drawRoundRectStroke(tint, s, w, 0.12f, 0.18f, 0.88f, 0.68f, 0.16f)
      line(tint, s, w, 0.30f, 0.68f, 0.28f, 0.88f)
      line(tint, s, w, 0.28f, 0.88f, 0.46f, 0.68f)
    }

    // 勋章:一枚圆章 + 下面两条绶带
    Ng2nIcon.WORKSPACE_PREMIUM -> {
      drawCircle(tint, w * 0.26f, Offset(w * 0.5f, w * 0.36f), style = Stroke(s))
      line(tint, s, w, 0.36f, 0.58f, 0.28f, 0.90f)
      line(tint, s, w, 0.28f, 0.90f, 0.50f, 0.78f)
      line(tint, s, w, 0.64f, 0.58f, 0.72f, 0.90f)
      line(tint, s, w, 0.72f, 0.90f, 0.50f, 0.78f)
    }

    // 「加进列表」:三条横线 + 一个加号
    Ng2nIcon.LIBRARY_ADD -> {
      hLine(tint, s, w, 0.14f, 0.66f, 0.26f)
      hLine(tint, s, w, 0.14f, 0.66f, 0.50f)
      hLine(tint, s, w, 0.14f, 0.46f, 0.74f)
      hLine(tint, s, w, 0.58f, 0.92f, 0.74f)
      line(tint, s, w, 0.75f, 0.57f, 0.75f, 0.91f)
    }

    Ng2nIcon.BOOKMARK -> {
      val mark = Path().apply {
        moveTo(w * 0.26f, w * 0.14f)
        lineTo(w * 0.74f, w * 0.14f)
        lineTo(w * 0.74f, w * 0.88f)
        lineTo(w * 0.50f, w * 0.66f)
        lineTo(w * 0.26f, w * 0.88f)
        close()
      }
      drawPath(mark, tint, style = Stroke(s, join = StrokeJoin.Round))
    }

    // 带星的文件夹
    Ng2nIcon.FOLDER_SPECIAL -> {
      drawPath(folderPath(w), tint, style = Stroke(s, join = StrokeJoin.Round))
      drawCircle(tint, w * 0.08f, Offset(w * 0.5f, w * 0.60f))
    }

    Ng2nIcon.WARNING -> {
      val tri = Path().apply {
        moveTo(w * 0.5f, w * 0.14f)
        lineTo(w * 0.92f, w * 0.84f)
        lineTo(w * 0.08f, w * 0.84f)
        close()
      }
      drawPath(tri, tint, style = Stroke(s, join = StrokeJoin.Round))
      line(tint, s, w, 0.5f, 0.40f, 0.5f, 0.62f)
      drawCircle(tint, w * 0.055f, Offset(w * 0.5f, w * 0.74f))
    }

    Ng2nIcon.ARTICLE -> {
      drawRoundRectStroke(tint, s, w, 0.18f, 0.12f, 0.82f, 0.88f, 0.10f)
      for (row in 0..2) hLine(tint, s, w, 0.30f, if (row == 2) 0.58f else 0.70f, 0.32f + row * 0.18f)
    }

    Ng2nIcon.REPLY -> {
      line(tint, s, w, 0.36f, 0.24f, 0.14f, 0.44f)
      line(tint, s, w, 0.14f, 0.44f, 0.36f, 0.64f)
      val arc = Path().apply {
        moveTo(w * 0.14f, w * 0.44f)
        lineTo(w * 0.62f, w * 0.44f)
        quadraticTo(w * 0.88f, w * 0.44f, w * 0.88f, w * 0.82f)
      }
      drawPath(arc, tint, style = Stroke(s, cap = StrokeCap.Round))
    }

    // 循环箭头(缓存)
    Ng2nIcon.CACHED -> {
      drawArc(
        color = tint,
        startAngle = 30f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(w * 0.18f, w * 0.18f),
        size = Size(w * 0.64f, w * 0.64f),
        style = Stroke(s, cap = StrokeCap.Round),
      )
      line(tint, s, w, 0.74f, 0.10f, 0.80f, 0.34f)
      line(tint, s, w, 0.80f, 0.34f, 0.56f, 0.30f)
    }

    Ng2nIcon.SMS -> {
      drawRoundRectStroke(tint, s, w, 0.10f, 0.18f, 0.90f, 0.68f, 0.14f)
      line(tint, s, w, 0.28f, 0.68f, 0.26f, 0.90f)
      line(tint, s, w, 0.26f, 0.90f, 0.44f, 0.68f)
      for (col in 0..2) drawCircle(tint, w * 0.055f, Offset(w * (0.34f + col * 0.16f), w * 0.43f))
    }

    // 铃铛 + 两侧声波
    Ng2nIcon.NOTIFICATIONS_ACTIVE -> {
      val bell = Path().apply {
        moveTo(w * 0.26f, w * 0.70f)
        lineTo(w * 0.26f, w * 0.46f)
        quadraticTo(w * 0.26f, w * 0.20f, w * 0.50f, w * 0.20f)
        quadraticTo(w * 0.74f, w * 0.20f, w * 0.74f, w * 0.46f)
        lineTo(w * 0.74f, w * 0.70f)
        close()
      }
      drawPath(bell, tint, style = Stroke(s, join = StrokeJoin.Round))
      hLine(tint, s, w, 0.18f, 0.82f, 0.70f)
      drawCircle(tint, w * 0.075f, Offset(w * 0.5f, w * 0.84f), style = Stroke(s))
    }

    Ng2nIcon.SETTINGS -> {
      drawCircle(tint, w * 0.16f, Offset(w * 0.5f, w * 0.5f), style = Stroke(s))
      drawCircle(tint, w * 0.36f, Offset(w * 0.5f, w * 0.5f), style = Stroke(s))
      for (index in 0..3) {
        val angle = Math.PI / 4 + index * Math.PI / 2
        val dx = kotlin.math.cos(angle).toFloat()
        val dy = kotlin.math.sin(angle).toFloat()
        line(
          tint, s, w,
          0.5f + dx * 0.30f, 0.5f + dy * 0.30f,
          0.5f + dx * 0.46f, 0.5f + dy * 0.46f,
        )
      }
    }

    Ng2nIcon.INFO -> {
      drawCircle(tint, w * 0.38f, Offset(w * 0.5f, w * 0.5f), style = Stroke(s))
      drawCircle(tint, w * 0.055f, Offset(w * 0.5f, w * 0.30f))
      line(tint, s, w, 0.5f, 0.44f, 0.5f, 0.72f)
    }

    // 漏斗
    Ng2nIcon.FILTER_ALT -> {
      val funnel = Path().apply {
        moveTo(w * 0.12f, w * 0.20f)
        lineTo(w * 0.88f, w * 0.20f)
        lineTo(w * 0.58f, w * 0.54f)
        lineTo(w * 0.58f, w * 0.86f)
        lineTo(w * 0.42f, w * 0.74f)
        lineTo(w * 0.42f, w * 0.54f)
        close()
      }
      drawPath(funnel, tint, style = Stroke(s, join = StrokeJoin.Round))
    }

    Ng2nIcon.REFRESH -> {
      drawArc(
        color = tint,
        startAngle = 210f,
        sweepAngle = 290f,
        useCenter = false,
        topLeft = Offset(w * 0.18f, w * 0.18f),
        size = Size(w * 0.64f, w * 0.64f),
        style = Stroke(s, cap = StrokeCap.Round),
      )
      line(tint, s, w, 0.20f, 0.14f, 0.24f, 0.40f)
      line(tint, s, w, 0.24f, 0.40f, 0.48f, 0.32f)
    }

    // 树形结构:一个父节点连两个子节点
    Ng2nIcon.ACCOUNT_TREE -> {
      drawRoundRectStroke(tint, s, w, 0.34f, 0.08f, 0.66f, 0.28f, 0.05f)
      drawRoundRectStroke(tint, s, w, 0.06f, 0.72f, 0.38f, 0.92f, 0.05f)
      drawRoundRectStroke(tint, s, w, 0.62f, 0.72f, 0.94f, 0.92f, 0.05f)
      line(tint, s, w, 0.5f, 0.28f, 0.5f, 0.50f)
      hLine(tint, s, w, 0.22f, 0.78f, 0.50f)
      line(tint, s, w, 0.22f, 0.50f, 0.22f, 0.72f)
      line(tint, s, w, 0.78f, 0.50f, 0.78f, 0.72f)
    }

    // 火苗
    Ng2nIcon.LOCAL_FIRE_DEPARTMENT -> {
      val flame = Path().apply {
        moveTo(w * 0.50f, w * 0.10f)
        quadraticTo(w * 0.78f, w * 0.36f, w * 0.74f, w * 0.60f)
        quadraticTo(w * 0.70f, w * 0.90f, w * 0.50f, w * 0.90f)
        quadraticTo(w * 0.30f, w * 0.90f, w * 0.26f, w * 0.60f)
        quadraticTo(w * 0.24f, w * 0.42f, w * 0.40f, w * 0.34f)
        quadraticTo(w * 0.42f, w * 0.24f, w * 0.50f, w * 0.10f)
        close()
      }
      drawPath(flame, tint, style = Stroke(s, join = StrokeJoin.Round))
    }

    // 云 + 一道斜杠
    Ng2nIcon.CLOUD_OFF -> {
      drawPath(cloudPath(w), tint, style = Stroke(s, join = StrokeJoin.Round))
      line(tint, s, w, 0.14f, 0.16f, 0.86f, 0.86f)
    }

    // 锥形瓶(实验室)
    Ng2nIcon.SCIENCE -> {
      val flask = Path().apply {
        moveTo(w * 0.38f, w * 0.12f)
        lineTo(w * 0.38f, w * 0.42f)
        lineTo(w * 0.16f, w * 0.82f)
        quadraticTo(w * 0.10f, w * 0.92f, w * 0.22f, w * 0.92f)
        lineTo(w * 0.78f, w * 0.92f)
        quadraticTo(w * 0.90f, w * 0.92f, w * 0.84f, w * 0.82f)
        lineTo(w * 0.62f, w * 0.42f)
        lineTo(w * 0.62f, w * 0.12f)
      }
      drawPath(flask, tint, style = Stroke(s, join = StrokeJoin.Round))
      hLine(tint, s, w, 0.32f, 0.68f, 0.12f)
    }

    // ---- 票 17a 追加 ----

    // 钟面 + 逆时针箭头
    Ng2nIcon.HISTORY -> {
      drawArc(
        color = tint,
        startAngle = 140f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(w * 0.16f, w * 0.16f),
        size = Size(w * 0.68f, w * 0.68f),
        style = Stroke(s, cap = StrokeCap.Round),
      )
      line(tint, s, w, 0.10f, 0.26f, 0.24f, 0.36f)
      line(tint, s, w, 0.24f, 0.36f, 0.10f, 0.48f)
      line(tint, s, w, 0.50f, 0.34f, 0.50f, 0.52f)
      line(tint, s, w, 0.50f, 0.52f, 0.66f, 0.60f)
    }

    // 垃圾桶:盖 + 桶身 + 两道竖纹
    Ng2nIcon.DELETE -> {
      hLine(tint, s, w, 0.16f, 0.84f, 0.26f)
      hLine(tint, s, w, 0.38f, 0.62f, 0.16f)
      drawPath(
        Path().apply {
          moveTo(w * 0.24f, w * 0.26f)
          lineTo(w * 0.30f, w * 0.86f)
          lineTo(w * 0.70f, w * 0.86f)
          lineTo(w * 0.76f, w * 0.26f)
        },
        tint,
        style = Stroke(s, join = StrokeJoin.Round),
      )
      line(tint, s, w, 0.42f, 0.40f, 0.44f, 0.72f)
      line(tint, s, w, 0.58f, 0.40f, 0.56f, 0.72f)
    }

    // 垃圾桶 + 左上三道「扫走」的线
    Ng2nIcon.DELETE_SWEEP -> {
      for (row in 0..2) hLine(tint, s, w, 0.08f, 0.34f - row * 0.04f, 0.26f + row * 0.18f)
      drawPath(
        Path().apply {
          moveTo(w * 0.50f, w * 0.30f)
          lineTo(w * 0.56f, w * 0.86f)
          lineTo(w * 0.86f, w * 0.86f)
          lineTo(w * 0.92f, w * 0.30f)
        },
        tint,
        style = Stroke(s, join = StrokeJoin.Round),
      )
      hLine(tint, s, w, 0.46f, 0.96f, 0.30f)
      hLine(tint, s, w, 0.62f, 0.80f, 0.20f)
    }

    // 铅笔:笔身一条四边形,笔尖落在左下角
    Ng2nIcon.EDIT -> {
      drawPath(
        Path().apply {
          moveTo(w * 0.14f, w * 0.86f)
          lineTo(w * 0.24f, w * 0.60f)
          lineTo(w * 0.62f, w * 0.22f)
          lineTo(w * 0.78f, w * 0.38f)
          lineTo(w * 0.40f, w * 0.76f)
          close()
        },
        tint,
        style = Stroke(s, join = StrokeJoin.Round),
      )
      // 笔杆与橡皮头之间那道分界
      line(tint, s, w, 0.54f, 0.30f, 0.70f, 0.46f)
    }

    Ng2nIcon.FOLDER -> drawPath(folderPath(w), tint, style = Stroke(s, join = StrokeJoin.Round))

    Ng2nIcon.CREATE_NEW_FOLDER -> {
      drawPath(folderPath(w), tint, style = Stroke(s, join = StrokeJoin.Round))
      hLine(tint, s, w, 0.36f, 0.64f, 0.58f)
      line(tint, s, w, 0.50f, 0.44f, 0.50f, 0.72f)
    }

    Ng2nIcon.EXPAND_MORE -> {
      line(tint, s, w, 0.22f, 0.38f, 0.50f, 0.66f)
      line(tint, s, w, 0.50f, 0.66f, 0.78f, 0.38f)
    }

    Ng2nIcon.RADIO_BUTTON_UNCHECKED ->
      drawCircle(tint, w * 0.36f, Offset(w * 0.5f, w * 0.5f), style = Stroke(s))

    Ng2nIcon.RADIO_BUTTON_CHECKED -> {
      drawCircle(tint, w * 0.36f, Offset(w * 0.5f, w * 0.5f), style = Stroke(s))
      drawCircle(tint, w * 0.19f, Offset(w * 0.5f, w * 0.5f))
    }

    Ng2nIcon.CHECK_BOX_OUTLINE_BLANK ->
      drawRoundRectStroke(tint, s, w, 0.14f, 0.14f, 0.86f, 0.86f, 0.12f)

    Ng2nIcon.CHECK_BOX -> {
      drawRoundRectStroke(tint, s, w, 0.14f, 0.14f, 0.86f, 0.86f, 0.12f)
      line(tint, s, w, 0.30f, 0.52f, 0.44f, 0.66f)
      line(tint, s, w, 0.44f, 0.66f, 0.72f, 0.34f)
    }

    // 向下的箭头 + 托底
    Ng2nIcon.DOWNLOAD -> {
      line(tint, s, w, 0.50f, 0.14f, 0.50f, 0.62f)
      line(tint, s, w, 0.30f, 0.44f, 0.50f, 0.64f)
      line(tint, s, w, 0.70f, 0.44f, 0.50f, 0.64f)
      hLine(tint, s, w, 0.18f, 0.82f, 0.86f)
    }

    // @:中间一个小圆 + 外面一圈缺口弧 + 右侧收尾的一小段
    Ng2nIcon.ALTERNATE_EMAIL -> {
      drawCircle(tint, w * 0.17f, Offset(w * 0.5f, w * 0.5f), style = Stroke(s))
      drawArc(
        color = tint,
        startAngle = -40f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(w * 0.12f, w * 0.12f),
        size = Size(w * 0.76f, w * 0.76f),
        style = Stroke(s, cap = StrokeCap.Round),
      )
      hLine(tint, s, w, 0.67f, 0.88f, 0.50f)
    }

    // 贴条:右下角折起来的便签
    Ng2nIcon.STICKY_NOTE_2 -> {
      drawPath(
        Path().apply {
          moveTo(w * 0.16f, w * 0.16f)
          lineTo(w * 0.84f, w * 0.16f)
          lineTo(w * 0.84f, w * 0.60f)
          lineTo(w * 0.60f, w * 0.84f)
          lineTo(w * 0.16f, w * 0.84f)
          close()
        },
        tint,
        style = Stroke(s, join = StrokeJoin.Round),
      )
      line(tint, s, w, 0.84f, 0.60f, 0.60f, 0.60f)
      line(tint, s, w, 0.60f, 0.60f, 0.60f, 0.84f)
      hLine(tint, s, w, 0.28f, 0.72f, 0.34f)
      hLine(tint, s, w, 0.28f, 0.56f, 0.48f)
    }

    // 竖起的大拇指:一只手掌 + 左边的袖口方块
    Ng2nIcon.THUMB_UP -> {
      drawPath(
        Path().apply {
          moveTo(w * 0.38f, w * 0.86f)
          lineTo(w * 0.38f, w * 0.44f)
          lineTo(w * 0.56f, w * 0.12f)
          quadraticTo(w * 0.70f, w * 0.14f, w * 0.64f, w * 0.36f)
          lineTo(w * 0.88f, w * 0.36f)
          lineTo(w * 0.78f, w * 0.86f)
          close()
        },
        tint,
        style = Stroke(s, join = StrokeJoin.Round),
      )
      drawRoundRectStroke(tint, s, w, 0.12f, 0.44f, 0.32f, 0.86f, 0.06f)
    }
  }
}

// ---------------------------------------------------------------- 画笔

private fun DrawScope.line(
  tint: Color,
  stroke: Float,
  w: Float,
  x1: Float,
  y1: Float,
  x2: Float,
  y2: Float,
) = drawLine(tint, Offset(w * x1, w * y1), Offset(w * x2, w * y2), stroke, StrokeCap.Round)

private fun DrawScope.hLine(tint: Color, stroke: Float, w: Float, x1: Float, x2: Float, y: Float) =
  line(tint, stroke, w, x1, y, x2, y)

private fun DrawScope.drawRoundRectStroke(
  tint: Color,
  stroke: Float,
  w: Float,
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  radius: Float,
) = drawRoundRect(
  color = tint,
  topLeft = Offset(w * left, w * top),
  size = Size(w * (right - left), w * (bottom - top)),
  cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * radius),
  style = Stroke(stroke, join = StrokeJoin.Round),
)

/** 正五角星,外接圆半径 0.40w、内半径 0.17w。 */
private fun starPath(w: Float): Path {
  val path = Path()
  val cx = w * 0.5f
  val cy = w * 0.52f
  val outer = w * 0.40f
  val inner = w * 0.17f
  for (index in 0 until 10) {
    val radius = if (index % 2 == 0) outer else inner
    val angle = -Math.PI / 2 + index * Math.PI / 5
    val x = cx + (radius * kotlin.math.cos(angle)).toFloat()
    val y = cy + (radius * kotlin.math.sin(angle)).toFloat()
    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
  }
  path.close()
  return path
}

private fun folderPath(w: Float): Path = Path().apply {
  moveTo(w * 0.10f, w * 0.82f)
  lineTo(w * 0.10f, w * 0.22f)
  lineTo(w * 0.42f, w * 0.22f)
  lineTo(w * 0.52f, w * 0.34f)
  lineTo(w * 0.90f, w * 0.34f)
  lineTo(w * 0.90f, w * 0.82f)
  close()
}

/**
 * 云朵轮廓:三个圆并一条底边。`Path.op(UNION)` 把它们焊成一条外轮廓,
 * 描边时才不会在交界处画出内部的弧。
 */
private fun cloudPath(w: Float): Path {
  val a = Path().apply { addOval(Rect(Offset(w * 0.30f, w * 0.28f), Size(w * 0.34f, w * 0.34f))) }
  val b = Path().apply { addOval(Rect(Offset(w * 0.12f, w * 0.44f), Size(w * 0.30f, w * 0.30f))) }
  val c = Path().apply { addOval(Rect(Offset(w * 0.56f, w * 0.42f), Size(w * 0.32f, w * 0.32f))) }
  val base = Path().apply {
    addRect(Rect(Offset(w * 0.24f, w * 0.56f), Size(w * 0.52f, w * 0.18f)))
  }
  val union = Path()
  union.op(a, b, PathOperation.Union)
  union.op(union, c, PathOperation.Union)
  union.op(union, base, PathOperation.Union)
  return union
}
