package com.chasel.ng2n.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.ui.theme.*

@Composable
fun QuickActionComposer(state: TopicAiState, onDraft: (String) -> Unit, onSend: () -> Unit,
  onStop: () -> Unit, onAction: (QuickAction) -> Unit) {
  val colors = LocalNg2nColors.current
  val actions = BuiltinQuickActions.forEntry(state.entryKind, state.floorEntry)
  var value by remember(state.conversationId) { mutableStateOf(TextFieldValue(state.draft)) }
  if (value.text != state.draft) value = TextFieldValue(state.draft, androidx.compose.ui.text.TextRange(state.draft.length))
  var forced by remember(state.conversationId) { mutableStateOf(false) }
  var suppressed by remember(state.conversationId, state.draft) { mutableStateOf(false) }
  var index by remember(state.conversationId, state.draft) { mutableIntStateOf(0) }
  var navigated by remember(state.conversationId, state.draft) { mutableStateOf(false) }
  val query = BuiltinQuickActions.query(state.draft)
  val enabled = !state.busy && !state.unsaved && state.turns.lastOrNull()?.card != "budget"
  val open = enabled && !suppressed && (forced || query != null)
  val matches = BuiltinQuickActions.filter(actions, query.orEmpty())
  fun close() { forced = false; suppressed = true }
  fun select(action: QuickAction) { close(); onAction(action) }
  BackHandler(open) { close() }
  val promptFocus = remember { FocusRequester() }
  // 浮层是非获焦窗口，按键只落在当前获焦控件上。菜单打开时把焦点放回输入框，
  // 否则点 / 按钮之后的 ↓ 会把焦点移到浮层背后的聊天内容，出现两处高亮且 Esc 无效。
  LaunchedEffect(open) { if (open) runCatching { promptFocus.requestFocus() } }
  fun handleKey(event: KeyEvent): Boolean =
    if (event.type != KeyEventType.KeyDown || value.composition != null) false
    else when {
      event.key == Key.Escape && open -> { close(); true }
      open && matches.isNotEmpty() && event.key in listOf(Key.DirectionDown, Key.DirectionUp) -> {
        index = (index + (if (event.key == Key.DirectionDown) 1 else -1) + matches.size) % matches.size
        navigated = true; true
      }
      open && (event.key == Key.Tab || (event.key == Key.Enter && !event.isShiftPressed)) -> {
        matches.getOrNull(index)?.let { select(it) }; true
      }
      event.key == Key.Enter && !event.isShiftPressed -> { if (enabled && state.draft.isNotBlank()) onSend(); true }
      else -> false
    }
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).onPreviewKeyEvent(::handleKey)) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(0.dp)) {
      val menuWidth = maxWidth
      if (open) androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter, onDismissRequest = { close() },
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
        // 浮层内容直接显示：入场动画要靠弹窗自己的首帧推进，页面其余部分静止时这一帧不会到来，
        // 菜单会停在动画起点（不可见）。点 / 按钮打开时页面正好没有其他动画，正是这种情况。
        Column(Modifier.width(menuWidth).heightIn(max = 260.dp).testTag("ai-quick-menu")
          .aiShadow(AiShadow.RAISED, RoundedCornerShape(14.dp)).background(colors.surface, RoundedCornerShape(14.dp)).padding(6.dp)
          .verticalScroll(rememberScrollState())) {
          val indication = LocalIndication.current
          matches.forEachIndexed { i, action ->
            // 键盘移动后停用按压反馈并重建交互源，避免点击残留的高亮与当前项同时出现。
            val interaction = remember(navigated) { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().height(48.dp).background(if (navigated && i == index) colors.primaryContainer else colors.surface,
              RoundedCornerShape(8.dp)).clickable(interaction, if (navigated) null else indication) { select(action) }
              .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              QuickActionBolt(Modifier.padding(end = 10.dp))
              Column { Text(action.label, color = colors.fg, fontSize = 14.sp); Text(action.description, color = colors.meta, fontSize = 11.5.sp) }
            }
          }
          if (matches.isEmpty()) Text("没有匹配「${query.orEmpty()}」的快捷操作", Modifier.heightIn(min = 44.dp).padding(8.dp), color = colors.meta, fontSize = 12.5.sp)
          HorizontalDivider(color = colors.divider)
          Text("输入文字筛选快捷操作", Modifier.padding(8.dp), color = colors.meta, fontSize = 11.5.sp)
        }
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      actions.forEach { action ->
        SuggestionChip(onClick = { select(action) }, enabled = enabled, label = { Text(action.label, fontSize = 12.sp) }, modifier = Modifier.heightIn(min = 32.dp))
      }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).aiShadow(AiShadow.CARD, RoundedCornerShape(16.dp))
      .background(colors.surface, RoundedCornerShape(16.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { if (open) close() else { forced = true; suppressed = false } }, enabled = enabled,
        modifier = Modifier.width(40.dp), contentPadding = PaddingValues(0.dp)) { Text("/", color = colors.meta) }
      BasicTextField(value, { value = it; forced = false; onDraft(it.text) },
        Modifier.weight(1f).testTag("ai-prompt").heightIn(min = 44.dp).focusRequester(promptFocus)
          .onPreviewKeyEvent(::handleKey).padding(horizontal = 6.dp, vertical = 12.dp),
        textStyle = TextStyle(color = colors.fg, fontSize = 15.sp), maxLines = 4, cursorBrush = SolidColor(colors.primary),
        decorationBox = { field -> Box { if (state.draft.isEmpty()) Text(if (state.busy) "正在回答，可随时停止" else "继续追问，输入 / 使用快捷操作", color = colors.meta, fontSize = 15.sp); field() } })
      if (state.busy) TextButton(onClick = onStop) { Text("停止", color = colors.danger) }
      else TextButton(onClick = onSend, enabled = enabled && state.draft.isNotBlank()) { Text("发送") }
    }
  }
}

@Composable
internal fun QuickActionBolt(modifier: Modifier = Modifier) {
  val color = LocalNg2nColors.current.primary
  androidx.compose.foundation.Canvas(modifier.size(16.dp)) {
    val path = androidx.compose.ui.graphics.Path().apply {
      moveTo(size.width * .58f, 0f); lineTo(size.width * .15f, size.height * .58f)
      lineTo(size.width * .46f, size.height * .58f); lineTo(size.width * .38f, size.height)
      lineTo(size.width * .88f, size.height * .38f); lineTo(size.width * .56f, size.height * .38f); close()
    }
    drawPath(path, color)
  }
}
