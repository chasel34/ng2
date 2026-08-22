package com.chasel.ng2n.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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

/**
 * 对话框类浮层 —— 直译 RN 侧 `ui/overlay.tsx` + `ui/input-dialog.tsx` + `ui/confirm-dialog.tsx`。
 *
 * 入场动效照设计稿 952–953 行:遮罩 `omfade .18s`、面板 `ompop .2s`
 * (淡入 + 从 .94 放到 1),两条同时起跑、遮罩先落。
 *
 * 铺在页面里而不是用 `androidx.compose.ui.window.Dialog`:后者会新起一个窗口,
 * 遮罩与面板落在不同的合成层上,两条动画的起跑时刻对不齐;而设计稿要的正是
 * 「同时起跑」。返回键关闭由 [BackHandler] 接。
 */
@Composable
private fun DialogShell(
  open: Boolean,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  if (!open) return
  val colors = LocalNg2nColors.current
  BackHandler(enabled = true, onBack = onDismiss)

  // 条件渲染 ⇒ 只有入场;关的时候整块被摘掉(与 RN 侧同一条边界)
  var started by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { started = true }
  val scrim by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(Motion.DURATION_QUICK, easing = Motion.easeStandard),
    label = "dialog-scrim",
  )
  val pop by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(Motion.DURATION_BASE, easing = Motion.easeStandard),
    label = "dialog-pop",
  )

  Box(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .matchParentSize()
        .drawBehind { drawRect(colors.scrim, alpha = scrim) }
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
        .scale(Motion.POP_SCALE + (1f - Motion.POP_SCALE) * pop)
        .alpha(pop)
        .shadow(Elevation.level2, RoundedCornerShape(Radius.dialog))
        .clip(RoundedCornerShape(Radius.dialog))
        .background(colors.menu)
        // 面板本身吃掉点击,不然点在面板上会穿到遮罩去
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        ),
    ) { content() }
  }
}

/** 「标题 + 一段正文 + 取消/确定」。危险操作(清空/删除)把确定钮染成 danger。 */
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

/**
 * 「标题 + 一行下划线输入 + 取消/确定」。
 *
 * [error] 是输入不合法时就地顶掉 [hint] 的红字(「由 URL 读取」:链接解不开
 * 不跳转、不关框);改了输入立刻把红字撤掉,不该赖到下一次点确定才刷新。
 */
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
  onValueChange: (String) -> Unit = {},
) {
  val colors = LocalNg2nColors.current
  DialogShell(open = open, onDismiss = onCancel) {
    var value by remember { mutableStateOf(initialValue) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = {
      keyboard?.hide()
      onConfirm(value)
    }

    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = Spacing.row)) {
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
          singleLine = true,
          textStyle = TextStyle(fontSize = 16.sp, color = colors.fg),
          cursorBrush = SolidColor(colors.primary),
          keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Go),
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

@Composable
private fun DialogActions(
  confirmLabel: String,
  destructive: Boolean,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
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
        .clickable(onClick = onCancel)
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
        .background(if (destructive) colors.danger else colors.primary)
        .clickable(onClick = onConfirm)
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
