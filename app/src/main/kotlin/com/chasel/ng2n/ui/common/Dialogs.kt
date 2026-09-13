package com.chasel.ng2n.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

@Composable
internal fun DialogShell(
  open: Boolean,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  val visibility = rememberVisibilityTransition(open)
  if (!visibility.currentState && !visibility.targetState && !visibility.isRunning) return
  val colors = LocalNg2nColors.current
  BackHandler(enabled = true, onBack = { if (open) onDismiss() })

  val pop by visibility.animateFloat(
    transitionSpec = { tween(if (targetState) Motion.DURATION_BASE else Motion.DURATION_EXIT, easing = Motion.easeStandard) },
    label = "overlay-pop",
  ) { if (it) 1f else 0f }

  Box(
    modifier = Modifier.fillMaxSize().guardExitingOverlay(open),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .matchParentSize()
        .drawBehind { drawRect(colors.scrim, alpha = pop) }
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClickLabel = "关闭对话框",
          onClick = onDismiss,
        )
        .semantics { contentDescription = "关闭对话框" },
    )
    Box(
      Modifier
        .fillMaxWidth()
        .padding(24.dp)
        .scale(Motion.POP_SCALE + (1f - Motion.POP_SCALE) * pop)
        .alpha(pop)
        .shadow(Elevation.level2, RoundedCornerShape(Radius.dialog))
        .clip(RoundedCornerShape(Radius.dialog))
        .background(colors.menu)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        ),
    ) { content() }
  }
}

@Composable
fun ConfirmDialog(
  open: Boolean,
  title: String,
  confirmLabel: String,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
  message: String? = null,
  destructive: Boolean = false,
) {
  val colors = LocalNg2nColors.current
  DialogShell(open = open, onDismiss = onCancel) {
    Column(
      Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = Spacing.row),
    ) {
      Text(
        text = title,
        style = TextStyle(
          fontSize = Typo.dialogTitle.size,
          lineHeight = Typo.dialogTitle.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg,
        ),
      )
      if (message != null) {
        Text(
          text = message,
          modifier = Modifier.padding(top = 9.dp),
          style = TextStyle(
            fontSize = Typo.dialogBody.size,
            lineHeight = Typo.dialogBody.lineHeight,
            color = colors.fg2,
          ),
        )
      }
      DialogActions(
        confirmLabel = confirmLabel,
        destructive = destructive,
        onCancel = onCancel,
        onConfirm = onConfirm,
      )
    }
  }
}

@Composable
fun InputDialog(
  open: Boolean,
  title: String,
  confirmLabel: String,
  onCancel: () -> Unit,
  onConfirm: (String) -> Unit,
  hint: String? = null,
  error: String? = null,
  initialValue: String = "",
  keyboardType: KeyboardType = KeyboardType.Text,
  multiline: Boolean = false,
  onValueChange: (String) -> Unit = {},
) {
  val colors = LocalNg2nColors.current
  DialogShell(open = open, onDismiss = onCancel) {
    var value by remember { mutableStateOf(initialValue) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    var submitAttempt by remember { mutableStateOf(0) }
    val shake = remember { Animatable(0f) }
    val shakeDistance = with(LocalDensity.current) { 4.dp.toPx() }
    LaunchedEffect(error, submitAttempt) {
      shake.snapTo(0f)
      if (error != null) {
        for (offset in listOf(-1f, 1f, -0.5f, 0.5f, 0f)) {
          shake.animateTo(offset, tween(45))
        }
      }
    }
    val submit = {
      submitAttempt += 1
      keyboard?.hide()
      onConfirm(value)
    }

    Column(
      Modifier
        .graphicsLayer { translationX = shake.value * shakeDistance }
        .animateContentSize(tween(Motion.DURATION_BASE, easing = Motion.easeStandard))
        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = Spacing.row),
    ) {
      Text(
        text = title,
        style = TextStyle(
          fontSize = Typo.dialogTitle.size,
          lineHeight = Typo.dialogTitle.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg,
        ),
      )
      CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
          handleColor = colors.primary,
          backgroundColor = colors.primary.copy(alpha = 0.3f),
        ),
      ) {
        BasicTextField(
          value = value,
          onValueChange = {
            value = it
            onValueChange(it)
          },
          singleLine = !multiline,
          minLines = if (multiline) MULTILINE_MIN_LINES else 1,
          maxLines = if (multiline) MULTILINE_MAX_LINES else 1,
          textStyle = TextStyle(fontSize = 16.sp, color = colors.fg),
          cursorBrush = SolidColor(colors.primary),
          keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Go,
          ),
          keyboardActions = KeyboardActions(onGo = { submit() }),
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg)
            .focusRequester(focus)
            .drawBehind {
              val y = size.height + 6.dp.toPx()
              drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(bottom = 8.dp),
        )
      }
      val note = error ?: hint
      if (note != null) {
        Text(
          text = note,
          modifier = Modifier.padding(top = 10.dp),
          style = TextStyle(
            fontSize = Typo.listSubtitle.size,
            lineHeight = Typo.listSubtitle.lineHeight,
            color = if (error != null) colors.danger else colors.meta,
          ),
        )
      }
      DialogActions(
        confirmLabel = confirmLabel,
        destructive = false,
        onCancel = onCancel,
        onConfirm = submit,
      )
    }
  }
}

private const val MULTILINE_MIN_LINES = 3
private const val MULTILINE_MAX_LINES = 8

@Composable
internal fun DialogActions(
  confirmLabel: String,
  destructive: Boolean,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
  enabled: Boolean = true,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = Spacing.row),
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(Radius.full))
        .clickable(enabled = enabled, onClick = onCancel)
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
      modifier = Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(Radius.full))
        .background(
          (if (destructive) colors.danger else colors.primary)
            .copy(alpha = if (enabled) 1f else 0.6f),
        )
        .clickable(enabled = enabled, onClick = onConfirm)
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
