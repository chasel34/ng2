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

private val SLIDER_LABEL_SIZE = 16.sp

private val SLIDER_VALUE_SIZE = 14.sp

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
        item(SETTINGS_TAIL_KEY) {
          Spacer(Modifier.height(30.dp))
          Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
      }
    }
    overlays()
  }
}

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

private val TRACK_WIDTH = 46.dp
private val TRACK_HEIGHT = 26.dp
private val TRACK_PADDING = 3.dp
private val KNOB_SIZE = 20.dp

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

data class SettingsOption<T>(val value: T, val label: String, val sub: String? = null)

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

private val SLIDER_AREA_HEIGHT = 66.dp
private val SLIDER_TRACK_HEIGHT = 3.dp
private val SLIDER_KNOB_SIZE = 18.dp

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
