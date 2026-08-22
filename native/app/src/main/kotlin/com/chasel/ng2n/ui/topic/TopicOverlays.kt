package com.chasel.ng2n.ui.topic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.bbcode.BBCodeCallbacks
import com.chasel.ng2n.ui.bbcode.BBCodeContent
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

/**
 * 弹出层:溢出菜单、输入对话框、签名弹窗、Snackbar。
 *
 * 与 [TopicChrome] 同一条归属说明:公共 UI 体系归票 17,这里只做主题三屏要的那一份。
 */

@Immutable
data class MenuItem(
  val key: String,
  val label: String,
  /** 这一条起一个新分组:上面画一条分割线 */
  val gapBefore: Boolean = false,
  val onClick: () -> Unit,
)

/**
 * 顶栏右上角的弹出菜单。设计稿:右侧留 8,圆角 14,条目高 50,弹出 .16s。
 *
 * 左手模式下整块镜像到左上角 —— 它是浮在内容上、要单手够的东西,
 * 缩放的原点也跟着换边,免得动画从一个够不着的角上长出来。
 */
@Composable
fun OverflowMenu(
  open: Boolean,
  items: List<MenuItem>,
  top: Dp,
  leftHanded: Boolean,
  onClose: () -> Unit,
) {
  if (!open) return
  val colors = LocalNg2nColors.current
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) { progress.animateTo(1f, tween(MENU_MS)) }

  Box(Modifier.fillMaxSize()) {
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
        .defaultMinSize(minWidth = 186.dp)
        .heightIn(max = 520.dp)
        .graphicsLayer {
          alpha = progress.value
          scaleX = POP_SCALE + (1f - POP_SCALE) * progress.value
          scaleY = scaleX
          transformOrigin = TransformOrigin(if (leftHanded) 0f else 1f, 0f)
        }
        .clip(RoundedCornerShape(Radius.lg))
        .background(colors.menu)
        .verticalScroll(rememberScrollState())
        .padding(vertical = 6.dp),
    ) {
      items.forEachIndexed { index, item ->
        // 第一条上面不画:面板顶上贴着一条线没有分组意义,还会怼到圆角上
        if (item.gapBefore && index > 0) Divider()
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(onClick = item.onClick)
            .padding(horizontal = 22.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(item.label, fontSize = Typo.notice.size, color = colors.fg)
        }
      }
    }
  }
}

private const val MENU_MS = 160
private const val POP_SCALE = 0.94f
private const val PANEL_MS = 220

/**
 * 设计稿那个「标题 + 一行下划线输入 + 取消/确定」的对话框。跳页用。
 */
@Composable
fun InputDialog(
  open: Boolean,
  title: String,
  hint: String,
  confirmLabel: String,
  initialValue: String,
  onCancel: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  if (!open) return
  val colors = LocalNg2nColors.current
  var value by remember { mutableStateOf(TextFieldValue(initialValue)) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

  DialogScaffold(onDismiss = onCancel) {
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
    DialogActions(
      cancelLabel = "取消",
      confirmLabel = confirmLabel,
      onCancel = onCancel,
      onConfirm = { onConfirm(value.text) },
    )
  }
}

/**
 * 「查看签名」弹窗(设计稿 `dialog:'sign'`:标题 + 正文 + 取消/知道了)。
 * 签名是 BBCode(可能带图带折叠),复用正文渲染器;没设置签名给一句占位。
 */
@Composable
fun SignatureDialog(state: SignatureDialogState?, onClose: () -> Unit) {
  if (state == null) return
  val colors = LocalNg2nColors.current
  DialogScaffold(onDismiss = onClose) {
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
        // 签名可以很长(装机单/许愿墙…),超出就在弹窗里滚
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
private fun DialogScaffold(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  val colors = LocalNg2nColors.current
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) { progress.animateTo(1f, tween(PANEL_MS)) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(colors.scrim)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onDismiss,
      )
      .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
          alpha = progress.value
          scaleX = POP_SCALE + (1f - POP_SCALE) * progress.value
          scaleY = scaleX
        }
        .clip(RoundedCornerShape(Radius.lg))
        .background(colors.menu)
        // 面板自己吃掉点击,不然点面板会被遮罩当成「点外面」关掉
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        )
        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = Spacing.row),
      content = { content() },
    )
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

/**
 * Snackbar(深底浅字 + 右侧一枚薄荷绿动作)。
 *
 * 与 toast 的分工照抄 RN 版:需要带动作(撤销 / 查看)或要在浅深主题下与设计稿 1:1 的
 * 提示走这里;纯气泡提示走系统 Toast。
 */
@Composable
fun SnackbarHost(message: SnackbarMessage?, dark: Boolean, onDismiss: () -> Unit) {
  if (message == null) return
  val progress = remember(message) { Animatable(0f) }
  LaunchedEffect(message) {
    progress.animateTo(1f, tween(PANEL_MS))
    kotlinx.coroutines.delay(AUTO_DISMISS_MS)
    onDismiss()
  }

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Row(
      modifier = Modifier
        .padding(start = Spacing.lg, end = Spacing.lg, bottom = SNACK_BOTTOM)
        .fillMaxWidth()
        .graphicsLayer {
          alpha = progress.value
          translationY = (1f - progress.value) * 42f
        }
        .clip(RoundedCornerShape(Radius.lg))
        .background(if (dark) SNACK_BG_DARK else SNACK_BG_LIGHT)
        .padding(horizontal = Spacing.lg, vertical = Spacing.row),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(
        text = message.text,
        fontSize = Typo.notice.size,
        lineHeight = Typo.notice.lineHeight,
        color = SNACK_FG,
        modifier = Modifier.weight(1f),
      )
      val label = message.actionLabel
      val action = message.action
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

/** 设计稿:snack 条距底 92(给 FAB 让路)、左右 16,滑入走 omup 的 .22s。 */
private val SNACK_BOTTOM = 92.dp

/** 自动消失时长。带撤销的提示给 4 秒反应时间。 */
private const val AUTO_DISMISS_MS = 4000L

/** 设计稿 `snackbarColors`(浅色 `#33322C`,深色 `#3A3A36`;字 `#F4F1E8`、动作 `#8FD8C9`)。 */
private val SNACK_BG_LIGHT = Color(0xFF33322C)
private val SNACK_BG_DARK = Color(0xFF3A3A36)
private val SNACK_FG = Color(0xFFF4F1E8)
private val SNACK_ACTION = Color(0xFF8FD8C9)
