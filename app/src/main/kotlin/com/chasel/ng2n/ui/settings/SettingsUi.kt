package com.chasel.ng2n.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlin.math.max
import kotlin.math.min

/**
 * 设置页的通用件 —— 直译 RN 侧 `ui/settings-shell.tsx` + `ui/settings-row.tsx` + `ui/slider.tsx`。
 *
 * 设置一级页、实验室二级页、字号屏的行长得完全一样,只有内容不同 —— 所以行本身抽在这儿,
 * 屏里只写数据。
 *
 * ## 与 RN 版的一处实现差异(语义不变)
 *
 * RN 侧那一屏要靠 `ProgressiveChildren`(首帧 1 行、每帧 +2)分帧挂载,否则二十几行
 * 自绘开关同步挂载要 16~19ms,push 动画第 1 帧就掉帧。Compose 这边用 [LazyColumn] ——
 * 只有落在视口里的行会进 composition,分帧那套补丁不必移植。
 */

// ---- 设计稿字号档(RN 侧 `ui/tokens.ts` 同名档,值一字未改)。
// 这几档只有设置树在用,先落在本文件里,不往共用的 `Typo` 里塞(并行期少一处冲突面)。

/** 滑杆标题 16 · 400 */
private val SLIDER_LABEL_SIZE = 16.sp

/** 滑杆取值气泡 14 · 600 */
private val SLIDER_VALUE_SIZE = 14.sp

/**
 * 设置页外壳:顶栏「← 标题」+ 一列可滚的行。
 *
 * RN 版那句「三屏向导拆成一屏」的理由一并保留:设置是随机访问的,用户带着
 * 「我要关签名档」进来要的是滚+找;而且设置项即时生效,没有「完成」这一步。
 *
 * [overlays] 单开一个口子而不是混在 [content] 里:对话框铺的是**视口**,
 * 混进滚动内容里会被摆到内容中段。
 */
/**
 * [SettingsShell] 自己补在列表末尾的那一项。用这个壳的屏(设置 / 实验室 / 字号)
 * 各自的 key 清单里都得带上它 —— 屏里再写一个同名 key 就是票 28 那种必崩。
 */
internal const val SETTINGS_TAIL_KEY: String = "settings-tail"

@Composable
fun SettingsShell(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  actions: @Composable () -> Unit = {},
  overlays: @Composable () -> Unit = {},
  content: LazyListScope.() -> Unit,
) {
  val colors = LocalNg2nColors.current
  Box(modifier.fillMaxSize().background(colors.bg)) {
    Column(Modifier.fillMaxSize()) {
      TopBar(paddingHorizontal = 4.dp) {
        TopBarButton(
          icon = Ng2nIcon.ARROW_BACK,
          size = 24.dp,
          box = 46.dp,
          contentDescription = "返回",
          onClick = onBack,
        )
        TopBarTitle(text = title, variant = TopBarTitleVariant.SUB, modifier = Modifier.weight(1f))
        actions()
      }
      LazyColumn(Modifier.fillMaxSize()) {
        content()
        // 最后一行的分隔线不该贴着屏幕底边
        item(SETTINGS_TAIL_KEY) {
          Spacer(Modifier.height(30.dp))
          Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
      }
    }
    overlays()
  }
}

/** 分组标题(设计稿:12.5/700 的主题色小标题;设置屏这一档不带字间距)。 */
@Composable
fun SettingsSection(text: String) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    modifier = Modifier.padding(
      top = Spacing.page,
      start = Spacing.page,
      end = Spacing.page,
      bottom = Spacing.sm,
    ),
    style = TextStyle(
      fontSize = Typo.caption.size,
      lineHeight = Typo.caption.lineHeight,
      fontWeight = FontWeight.Bold,
      color = colors.primary,
    ),
  )
}

/** 开关行。整行可点,点了就翻档(RN 版同)。 */
@Composable
fun SettingsSwitchRow(
  label: String,
  value: Boolean,
  sub: String? = null,
  onChange: (Boolean) -> Unit,
) {
  SettingsRow(
    label = label,
    sub = sub,
    onClick = { onChange(!value) },
    contentDescription = label,
    trailing = { SettingsSwitch(value) },
  )
}

/** 点进二级页或弹对话框的行,右侧是 chevron。 */
@Composable
fun SettingsNavRow(
  label: String,
  sub: String? = null,
  onClick: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  SettingsRow(
    label = label,
    sub = sub,
    onClick = onClick,
    contentDescription = label,
    trailing = { AppIcon(icon = Ng2nIcon.CHEVRON_RIGHT, tint = colors.meta, size = 20.dp) },
  )
}

@Composable
private fun SettingsRow(
  label: String,
  sub: String?,
  onClick: () -> Unit,
  contentDescription: String,
  trailing: @Composable () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = contentDescription, onClick = onClick)
      .semantics { this.contentDescription = contentDescription }
      .drawBehind {
        val y = size.height - 0.5.dp.toPx()
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
      }
      .padding(vertical = Spacing.row, horizontal = Spacing.page),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.row),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = label,
        style = TextStyle(
          fontSize = Typo.drawerItem.size,
          lineHeight = Typo.drawerItem.lineHeight,
          color = colors.fg,
        ),
      )
      if (!sub.isNullOrEmpty()) {
        Text(
          text = sub,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = Spacing.xs),
          style = TextStyle(
            fontSize = Typo.listMeta.size,
            lineHeight = Typo.listMeta.lineHeight,
            color = colors.meta,
          ),
        )
      }
    }
    trailing()
  }
}

/** 设计稿:轨道 46×26 圆角 13、内距 3,滑块 20 见方,开时右移 20。 */
private val TRACK_WIDTH = 46.dp
private val TRACK_HEIGHT = 26.dp
private val TRACK_PADDING = 3.dp
private val KNOB_SIZE = 20.dp

/**
 * 开关本体。自己画而不是用 Material3 的 `Switch`:后者的尺寸、圆角与滑块比例
 * 都跟设计稿差得远(RN 版拒绝平台 `Switch` 是同一个理由)。
 * 过渡走设计稿的 `transition:.18s`(动效 token 的 quick 档)。
 */
@Composable
fun SettingsSwitch(value: Boolean) {
  val colors = LocalNg2nColors.current
  val spec = tween<Float>(Motion.DURATION_QUICK, easing = Motion.easeStandard)
  val progress by animateFloatAsState(if (value) 1f else 0f, spec, label = "switch-knob")
  val track by animateColorAsState(
    targetValue = if (value) colors.primary else colors.track,
    animationSpec = tween(Motion.DURATION_QUICK, easing = Motion.easeStandard),
    label = "switch-track",
  )
  val travel = with(LocalDensity.current) { (TRACK_WIDTH - TRACK_PADDING * 2 - KNOB_SIZE).toPx() }

  Box(
    Modifier
      .size(TRACK_WIDTH, TRACK_HEIGHT)
      .clip(RoundedCornerShape(TRACK_HEIGHT / 2))
      .background(track)
      .padding(TRACK_PADDING),
    contentAlignment = Alignment.CenterStart,
  ) {
    Box(
      Modifier
        .size(KNOB_SIZE)
        .graphicsLayer { translationX = progress * travel }
        .shadow(Elevation.level1, CircleShape)
        .background(if (value) colors.onPrimary else colors.surface, CircleShape),
    )
  }
}

// ---------------------------------------------------------------- 单选对话框

/** 一个互斥档位。[sub] 是第二行灰字说明。 */
data class SettingsOption<T>(val value: T, val label: String, val sub: String? = null)

/**
 * 单选对话框(设计稿 `dialog:'theme'` 那种「标题 + 单选列表 + 取消/应用」)。
 *
 * 设置页里凡是「一组互斥档位」的行都用它:NGA 域名、主题风格、图片加载策略、
 * 网页数据源兜底档位。**选中先只改本地态,点「应用」才回调** —— 域名这种改了要
 * 重打请求的档位,手滑点中不该立刻生效(照抄 RN 版)。
 */
@Composable
fun <T> SettingsOptionDialog(
  open: Boolean,
  title: String,
  options: List<SettingsOption<T>>,
  value: T,
  onCancel: () -> Unit,
  onConfirm: (T) -> Unit,
  hint: String? = null,
  confirmLabel: String = "应用",
) {
  if (!open) return
  val colors = LocalNg2nColors.current
  // 每次打开都从当前生效的档位开始:上次点了取消,选中态不该留在那儿
  var picked by remember(value) { mutableStateOf(value) }

  SettingsDialogShell(onDismiss = onCancel) {
    Column(Modifier.padding(top = 22.dp, bottom = Spacing.row)) {
      Text(
        text = title,
        modifier = Modifier.padding(horizontal = 22.dp),
        style = TextStyle(
          fontSize = Typo.dialogTitle.size,
          lineHeight = Typo.dialogTitle.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg,
        ),
      )
      // 域名有五个、兜底档位有四个,长列表在面板里滚而不是把面板顶出屏幕
      LazyColumn(
        Modifier
          .padding(top = Spacing.md)
          .heightIn(max = 340.dp),
      ) {
        items(options.size, key = { options[it].label }) { index ->
          val option = options[index]
          val selected = option.value == picked
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .clickable(onClickLabel = option.label) { picked = option.value }
              .semantics { contentDescription = option.label }
              .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
          ) {
            RadioMark(selected)
            Column(Modifier.weight(1f)) {
              Text(
                text = option.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                  fontSize = Typo.menuItem.size,
                  lineHeight = Typo.menuItem.lineHeight,
                  fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                  color = if (selected) colors.primary else colors.fg,
                ),
              )
              if (option.sub != null) {
                Text(
                  text = option.sub,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  style = TextStyle(
                    fontSize = Typo.meta.size,
                    lineHeight = Typo.meta.lineHeight,
                    color = colors.meta,
                  ),
                )
              }
            }
          }
        }
      }
      if (hint != null) {
        Text(
          text = hint,
          modifier = Modifier.padding(horizontal = 22.dp, vertical = Spacing.sm),
          style = TextStyle(
            fontSize = Typo.listMeta.size,
            lineHeight = Typo.listMeta.lineHeight,
            color = colors.meta,
          ),
        )
      }
      SettingsDialogActions(
        confirmLabel = confirmLabel,
        onCancel = onCancel,
        onConfirm = { onConfirm(picked) },
      )
    }
  }
}

/** 单选圈。两个同心圆就够,不为它往共用图标集里加一档(并行期少一处冲突面)。 */
@Composable
private fun RadioMark(selected: Boolean) {
  val colors = LocalNg2nColors.current
  val tint = if (selected) colors.primary else colors.meta
  Box(
    Modifier
      .size(21.dp)
      .drawBehind {
        val radius = size.minDimension / 2f
        drawCircle(tint, radius - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
        if (selected) drawCircle(tint, radius * 0.48f)
      },
  )
}

/**
 * 对话框壳子。与 `ui/common/Dialogs.kt` 的 `DialogShell` 同款(遮罩 .18s + 面板 ompop .2s),
 * 那一个是 private 的,这里按同一份设计稿复刻;两处的取值来自同一组 [Motion] 常量,
 * 不会各说各话。
 */
@Composable
private fun SettingsDialogShell(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  val colors = LocalNg2nColors.current
  androidx.activity.compose.BackHandler(enabled = true, onBack = onDismiss)

  var started by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { started = true }
  val scrim by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(Motion.DURATION_QUICK, easing = Motion.easeStandard),
    label = "settings-dialog-scrim",
  )
  val pop by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(Motion.DURATION_BASE, easing = Motion.easeStandard),
    label = "settings-dialog-pop",
  )

  Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Box(
      Modifier
        .matchParentSize()
        .drawBehind { drawRect(colors.scrim, alpha = scrim) }
        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        .semantics { contentDescription = "关闭对话框" },
    )
    Box(
      Modifier
        .fillMaxWidth()
        .graphicsLayer {
          val scale = Motion.POP_SCALE + (1f - Motion.POP_SCALE) * pop
          scaleX = scale
          scaleY = scale
          alpha = pop
        }
        .shadow(Elevation.level2, RoundedCornerShape(Radius.dialog))
        .clip(RoundedCornerShape(Radius.dialog))
        .background(colors.menu)
        // 面板本身吃掉点击,不然点在面板上会穿到遮罩去
        .pointerInput(Unit) { detectTapGestures { } },
    ) { content() }
  }
}

@Composable
private fun SettingsDialogActions(
  confirmLabel: String,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
  destructive: Boolean = false,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = Spacing.row, start = 22.dp, end = 22.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(Radius.full))
        .clickable(onClickLabel = "取消", onClick = onCancel)
        .padding(horizontal = Spacing.lg),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "取消",
        style = TextStyle(
          fontSize = Typo.dialogAction.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg2,
        ),
      )
    }
    Box(
      Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(Radius.full))
        .background(if (destructive) colors.danger else colors.primary)
        .clickable(onClickLabel = confirmLabel, onClick = onConfirm)
        .padding(horizontal = Spacing.xl),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = confirmLabel,
        style = TextStyle(
          fontSize = Typo.dialogAction.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.onPrimary,
        ),
      )
    }
  }
}

// ---------------------------------------------------------------- 滑杆

/** 设计稿:轨道 3 高、圆钮 18、整块滑杆区 66 高。 */
private val SLIDER_AREA_HEIGHT = 66.dp
private val SLIDER_TRACK_HEIGHT = 3.dp
private val SLIDER_KNOB_SIZE = 18.dp

/**
 * 字号调节屏的滑杆(设计稿 `T.fontSliders`:取值气泡 + 3px 轨道 + 18 圆钮 + 两端 ±)。
 *
 * 自己画而不是用 Material3 的 `Slider`:要的形状很具体(气泡跟着钮走、两端带步进钮),
 * 而且这是全 app 唯一一处滑杆。
 *
 * 手势与 RN 版同:落手先跳到按住的位置,之后按位移接着拖。
 */
@Composable
fun SettingsSlider(
  label: String,
  text: String,
  ratio: Float,
  onSlide: (Float) -> Unit,
  onStep: (Int) -> Unit,
) {
  val colors = LocalNg2nColors.current
  var width by remember { mutableFloatStateOf(0f) }
  var bubbleWidth by remember { mutableFloatStateOf(0f) }
  // 手势回调不能进 pointerInput 的 key(换了会把正在进行的手势掐断),所以从 ref 里读
  val slide = remember { SliderCallback() }
  slide.onSlide = onSlide
  slide.width = width
  val density = LocalDensity.current

  Column(
    Modifier
      .fillMaxWidth()
      .drawBehind {
        val y = size.height - 0.5.dp.toPx()
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
      }
      .padding(top = Spacing.lg, start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xs),
  ) {
    Text(
      text = label,
      style = TextStyle(fontSize = SLIDER_LABEL_SIZE, lineHeight = 22.sp, color = colors.fg),
    )

    Box(
      Modifier
        .fillMaxWidth()
        .height(SLIDER_AREA_HEIGHT)
        .padding(top = 2.dp)
        .onSizeChanged { width = it.width.toFloat() },
    ) {
      val filled = width * ratio

      // 气泡以钮为中心。宽度随文字变(「1.70」比「17」宽),量出来再抵掉一半
      Box(
        Modifier
          .align(Alignment.TopStart)
          .padding(top = Spacing.xs)
          .graphicsLayer { translationX = filled - bubbleWidth / 2f }
          .onSizeChanged { bubbleWidth = it.width.toFloat() }
          .clip(RoundedCornerShape(3.dp))
          .background(colors.primary)
          .padding(horizontal = Spacing.row, vertical = 6.dp),
      ) {
        Text(
          text = text,
          style = TextStyle(
            fontSize = SLIDER_VALUE_SIZE,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onPrimary,
          ),
        )
      }

      // 轨道本身只有 3px 拖不住,给它套一条 34 高的可拖带
      Box(
        Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
          .height(34.dp)
          .padding(bottom = 20.dp - (34.dp - SLIDER_TRACK_HEIGHT) / 2)
          .pointerInput(Unit) {
            detectHorizontalDragGestures(
              onDragStart = { offset ->
                if (slide.width > 0f) {
                  slide.start = clamp01(offset.x / slide.width)
                  slide.onSlide(slide.start)
                }
              },
              onHorizontalDrag = { _, dragAmount ->
                if (slide.width > 0f) {
                  slide.start = clamp01(slide.start + dragAmount / slide.width)
                  slide.onSlide(slide.start)
                }
              },
            )
          }
          .pointerInput(Unit) {
            detectTapGestures { offset ->
              if (slide.width > 0f) slide.onSlide(clamp01(offset.x / slide.width))
            }
          },
        contentAlignment = Alignment.CenterStart,
      ) {
        Box(
          Modifier
            .fillMaxWidth()
            .height(SLIDER_TRACK_HEIGHT)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.track),
        )
        Box(
          Modifier
            .width(with(density) { max(0f, filled).toDp() })
            .height(SLIDER_TRACK_HEIGHT)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.primary),
        )
        Box(
          Modifier
            .size(SLIDER_KNOB_SIZE)
            .graphicsLayer {
              translationX = filled - SLIDER_KNOB_SIZE.toPx() / 2f
            }
            .background(colors.primary, CircleShape),
        )
      }

      // ± 压在轨道下方的两端,与可拖带有一小段重叠(设计稿本来就这样)
      StepButton(
        minus = true,
        label = "调小$label",
        modifier = Modifier.align(Alignment.BottomStart),
        onClick = { onStep(-1) },
      )
      StepButton(
        minus = false,
        label = "调大$label",
        modifier = Modifier.align(Alignment.BottomEnd),
        onClick = { onStep(1) },
      )
    }
  }
}

/** 滑杆手势的可变状态。`pointerInput` 的闭包只建一次,要改的量全从这里读。 */
private class SliderCallback {
  var width: Float = 0f
  var start: Float = 0f
  var onSlide: (Float) -> Unit = {}
}

@Composable
private fun StepButton(
  minus: Boolean,
  label: String,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Box(
    modifier
      .size(20.dp)
      .clip(CircleShape)
      .clickable(onClickLabel = label, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    if (minus) {
      // 「−」在共用图标集里没有一档,一条横线就够
      Box(
        Modifier
          .fillMaxSize()
          .drawBehind {
            val y = size.height / 2f
            drawLine(
              colors.meta,
              Offset(size.width * 0.18f, y),
              Offset(size.width * 0.82f, y),
              1.7.dp.toPx(),
            )
          },
      )
    } else {
      AppIcon(icon = Ng2nIcon.ADD, tint = colors.meta, size = 20.dp)
    }
  }
}

private fun clamp01(value: Float): Float = min(1f, max(0f, value))
