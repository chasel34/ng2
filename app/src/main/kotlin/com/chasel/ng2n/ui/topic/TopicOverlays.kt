package com.chasel.ng2n.ui.topic

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.data.bookmarks.BOOKMARK_NOTE_MAX
import com.chasel.ng2n.ui.bbcode.BBCodeCallbacks
import com.chasel.ng2n.ui.bbcode.BBCodeContent
import com.chasel.ng2n.ui.common.DialogShell
import com.chasel.ng2n.ui.common.MENU_ITEM_HEIGHT
import com.chasel.ng2n.ui.common.MENU_ITEM_PADDING
import com.chasel.ng2n.ui.common.MENU_MAX_HEIGHT
import com.chasel.ng2n.ui.common.MENU_MIN_WIDTH
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.common.guardExitingOverlay
import com.chasel.ng2n.ui.common.rememberVisibilityTransition
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

@Immutable
data class MenuItem(
  val key: String,
  val label: String,
  val gapBefore: Boolean = false,
  val onClick: () -> Unit,
)

@Composable
fun OverflowMenu(
  open: Boolean,
  items: List<MenuItem>,
  top: Dp,
  leftHanded: Boolean,
  onClose: () -> Unit,
) {
  val visibility = rememberVisibilityTransition(open)
  if (!visibility.currentState && !visibility.targetState && !visibility.isRunning) return
  val colors = LocalNg2nColors.current
  androidx.activity.compose.BackHandler { if (open) onClose() }
  val progress by visibility.animateFloat(
    transitionSpec = { tween(if (targetState) Motion.DURATION_MENU else Motion.DURATION_EXIT, easing = Motion.easeStandard) },
    label = "topic-menu",
  ) { if (it) 1f else 0f }

  Box(Modifier.fillMaxSize().guardExitingOverlay(open)) {
    Box(
      Modifier
        .fillMaxSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onClose,
        ),
    )
    Column(
      modifier = Modifier
        .align(if (leftHanded) Alignment.TopStart else Alignment.TopEnd)
        .padding(top = top, start = Spacing.sm, end = Spacing.sm)
        .defaultMinSize(minWidth = MENU_MIN_WIDTH)
        .width(IntrinsicSize.Max)
        .heightIn(max = MENU_MAX_HEIGHT)
        .graphicsLayer {
          alpha = progress
          scaleX = Motion.POP_SCALE + (1f - Motion.POP_SCALE) * progress
          scaleY = scaleX
          transformOrigin = TransformOrigin(if (leftHanded) 0f else 1f, 0f)
        }
        .clip(RoundedCornerShape(Radius.lg))
        .background(colors.menu)
        .verticalScroll(rememberScrollState())
        .padding(vertical = 6.dp),
    ) {
      items.forEachIndexed { index, item ->
        if (item.gapBefore && index > 0) Divider()
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ITEM_HEIGHT)
            .clickable(onClick = item.onClick)
            .padding(horizontal = MENU_ITEM_PADDING),
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(
            text = item.label,
            fontSize = Typo.menuItem.size,
            lineHeight = Typo.menuItem.lineHeight,
            color = colors.fg,
          )
        }
      }
    }
  }
}

@Composable
fun InputDialog(
  open: Boolean,
  title: String,
  hint: String,
  confirmLabel: String,
  initialValue: String,
  onCancel: () -> Unit,
  onConfirm: (String) -> Unit,
  targets: List<JumpTarget> = emptyList(),
  onPickTarget: (JumpTarget) -> Unit = {},
) {
  DialogScaffold(state = if (open) Unit else null, onDismiss = onCancel) {
    val colors = LocalNg2nColors.current
    var value by remember { mutableStateOf(TextFieldValue(initialValue)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Text(title, fontSize = Typo.section.size, fontWeight = FontWeight.SemiBold, color = colors.fg)
    Box(
      modifier = Modifier
        .padding(top = 9.dp)
        .fillMaxWidth()
        .background(colors.surface2, RoundedCornerShape(Radius.xs))
        .padding(horizontal = Spacing.md, vertical = 10.dp),
    ) {
      BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontSize = Typo.notice.size, color = colors.fg),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
      )
    }
    Text(
      text = hint,
      fontSize = Typo.listMeta.size,
      color = colors.meta,
      modifier = Modifier.padding(top = 6.dp),
    )
    if (targets.isNotEmpty()) {
      Box(
        Modifier
          .padding(top = Spacing.row, bottom = 6.dp)
          .fillMaxWidth()
          .height(1.dp)
          .background(colors.divider),
      )
      Column(
        Modifier
          .fillMaxWidth()
          .heightIn(max = JUMP_TARGETS_MAX_HEIGHT)
          .verticalScroll(rememberScrollState()),
      ) {
        targets.forEach { target -> JumpTargetRow(target = target, onClick = { onPickTarget(target) }) }
      }
    }
    DialogActions(
      cancelLabel = "取消",
      confirmLabel = confirmLabel,
      onCancel = onCancel,
      onConfirm = { onConfirm(value.text) },
    )
  }
}

@Composable
private fun JumpTargetRow(target: JumpTarget, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(44.dp)
      .clip(RoundedCornerShape(Radius.xs))
      .clickable(onClick = onClick)
      .padding(horizontal = Spacing.xs),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (target.resume) BookmarkIcon(tint = colors.link, size = 18.dp) else BookmarkAddedIcon(tint = colors.primary)
    Text(target.title, fontSize = Typo.dialogListItem.size, color = colors.link)
    Text(
      text = target.detail,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      fontSize = Typo.dialogListItem.size,
      color = colors.fg2,
      modifier = Modifier.weight(1f),
    )
  }
}

private val JUMP_TARGETS_MAX_HEIGHT = 264.dp

@Composable
fun BookmarkDialog(state: BookmarkDialogState?, onCancel: () -> Unit, onSave: (String) -> Unit) {
  DialogScaffold(state = state, onDismiss = onCancel) { state ->
    val colors = LocalNg2nColors.current
    var value by remember(state.pid, state.editing) {
      mutableStateOf(TextFieldValue(state.note, TextRange(state.note.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Text(
      if (state.editing) "编辑书签" else "加书签",
      fontSize = Typo.section.size,
      fontWeight = FontWeight.SemiBold,
      color = colors.fg,
    )
    Text(
      text = "第 ${state.lou} 楼 · ${state.author}",
      fontSize = Typo.listMeta.size,
      color = colors.meta,
      modifier = Modifier.padding(top = Spacing.xs),
    )
    Text(
      text = state.summary,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      fontSize = Typo.notice.size,
      lineHeight = Typo.notice.lineHeight,
      color = colors.fg2,
      modifier = Modifier
        .padding(top = 9.dp)
        .fillMaxWidth()
        .background(colors.surface2, RoundedCornerShape(Radius.xs))
        .padding(horizontal = Spacing.md, vertical = 10.dp),
    )
    Box(
      modifier = Modifier
        .padding(top = 9.dp)
        .fillMaxWidth()
        .background(colors.surface2, RoundedCornerShape(Radius.xs))
        .padding(horizontal = Spacing.md, vertical = 10.dp),
    ) {
      BasicTextField(
        value = value,
        onValueChange = { next ->
          value = if (next.text.length <= BOOKMARK_NOTE_MAX) {
            next
          } else {
            val cut = next.text.take(BOOKMARK_NOTE_MAX)
            TextFieldValue(cut, TextRange(cut.length))
          }
        },
        maxLines = 3,
        textStyle = TextStyle(fontSize = Typo.notice.size, color = colors.fg),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
          if (value.text.isEmpty()) {
            Text("备注（可选）", fontSize = Typo.notice.size, color = colors.meta)
          }
          inner()
        },
      )
    }
    Text(
      text = "${value.text.length} / $BOOKMARK_NOTE_MAX",
      fontSize = Typo.listMeta.size,
      color = colors.meta,
      textAlign = TextAlign.End,
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    DialogActions(
      cancelLabel = "取消",
      confirmLabel = "保存",
      onCancel = onCancel,
      onConfirm = { onSave(value.text) },
    )
  }
}

@Composable
fun SignatureDialog(state: SignatureDialogState?, onClose: () -> Unit) {
  DialogScaffold(state = state, onDismiss = onClose) { state ->
    val colors = LocalNg2nColors.current
    Text("查看签名", fontSize = Typo.section.size, fontWeight = FontWeight.SemiBold, color = colors.fg)
    Box(
      modifier = Modifier
        .padding(top = 9.dp)
        .heightIn(max = 340.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      val model = state.model
      if (model == null || model.isEmpty) {
        Text("${state.user.name} 没有设置签名", fontSize = Typo.notice.size, color = colors.meta)
      } else {
        BBCodeContent(model = model, callbacks = BBCodeCallbacks())
      }
    }
    DialogActions(
      cancelLabel = "取消",
      confirmLabel = "知道了",
      onCancel = onClose,
      onConfirm = onClose,
    )
  }
}

@Composable
private fun <T : Any> DialogScaffold(state: T?, onDismiss: () -> Unit, content: @Composable (T) -> Unit) {
  var retained by remember { mutableStateOf(state) }
  SideEffect { if (state != null) retained = state }
  DialogShell(open = state != null, onDismiss = onDismiss) {
    val visible = state ?: retained ?: return@DialogShell
    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = Spacing.row)) {
      content(visible)
    }
  }
}

@Composable
private fun DialogActions(
  cancelLabel: String,
  confirmLabel: String,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = Spacing.row),
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
  ) {
    Box(
      modifier = Modifier
        .height(40.dp)
        .clip(CircleShape)
        .clickable(onClick = onCancel)
        .padding(horizontal = Spacing.lg),
      contentAlignment = Alignment.Center,
    ) { Text(cancelLabel, fontSize = Typo.notice.size, color = colors.fg2) }
    Box(
      modifier = Modifier
        .height(40.dp)
        .clip(CircleShape)
        .background(colors.primary)
        .clickable(onClick = onConfirm)
        .padding(horizontal = Spacing.xl),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        confirmLabel,
        fontSize = Typo.notice.size,
        fontWeight = FontWeight.SemiBold,
        color = colors.onPrimary,
      )
    }
  }
}

@Composable
fun SnackbarHost(message: SnackbarMessage?, dark: Boolean, onDismiss: () -> Unit) {
  var retained by remember { mutableStateOf(message) }
  SideEffect { if (message != null) retained = message }
  val visibility = rememberVisibilityTransition(message != null)
  val visibleMessage = message ?: retained ?: return
  if (!visibility.currentState && !visibility.targetState && !visibility.isRunning) return
  val progress by visibility.animateFloat(
    transitionSpec = { tween(Motion.DURATION_PANEL, easing = Motion.easeStandard) },
    label = "topic-snackbar",
  ) { if (it) 1f else 0f }
  LaunchedEffect(message) {
    if (message != null) {
      kotlinx.coroutines.delay(autoDismissMs(message.action != null))
      onDismiss()
    }
  }
  val rise = with(LocalDensity.current) { Motion.RISE_OFFSET.dp.toPx() }
  val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Row(
      modifier = Modifier
        .padding(start = Spacing.lg, end = Spacing.lg, bottom = SNACK_BOTTOM + navBar)
        .fillMaxWidth()
        .guardExitingOverlay(message != null)
        .graphicsLayer {
          alpha = progress
          translationY = (1f - progress) * rise
        }
        .clip(RoundedCornerShape(Radius.lg))
        .background(if (dark) SNACK_BG_DARK else SNACK_BG_LIGHT)
        .padding(horizontal = Spacing.lg, vertical = Spacing.row),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(
        text = visibleMessage.text,
        fontSize = Typo.notice.size,
        lineHeight = Typo.notice.lineHeight,
        color = SNACK_FG,
        modifier = Modifier.weight(1f),
      )
      val label = visibleMessage.actionLabel
      val action = visibleMessage.action
      if (label != null && action != null) {
        Text(
          text = label,
          fontSize = Typo.listMeta.size,
          fontWeight = FontWeight.Bold,
          color = SNACK_ACTION,
          modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .clickable {
              onDismiss()
              action()
            }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
      }
    }
  }
}

private val SNACK_BOTTOM = 92.dp

private const val AUTO_DISMISS_MS = 4000L

private const val AUTO_DISMISS_ACTION_MS = 8000L

private fun autoDismissMs(hasAction: Boolean): Long =
  if (hasAction) AUTO_DISMISS_ACTION_MS else AUTO_DISMISS_MS

private val SNACK_BG_LIGHT = Color(0xFF33322C)
private val SNACK_BG_DARK = Color(0xFF3A3A36)
private val SNACK_FG = Color(0xFFF4F1E8)
private val SNACK_ACTION = Color(0xFF8FD8C9)
