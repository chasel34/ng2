package com.chasel.ng2n.ui.filters

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.validateFilterRule
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

private val KINDS = listOf(FilterRuleKind.USER, FilterRuleKind.KEYWORD, FilterRuleKind.CATEGORY)

private fun placeholderOf(kind: FilterRuleKind): String = when (kind) {
  FilterRuleKind.USER -> "例如 xtl150ok"
  FilterRuleKind.KEYWORD -> "例如 内部消息"
  FilterRuleKind.CATEGORY -> "例如 转帖(标题里 [] 括起来的那个词)"
}

private fun hintOf(kind: FilterRuleKind): String = when (kind) {
  FilterRuleKind.KEYWORD -> "命中标题或正文即屏蔽"
  FilterRuleKind.USER -> "按用户名精确匹配,大小写不敏感"
  FilterRuleKind.CATEGORY -> "匹配标题里方括号括起来的分类,如 [转帖]"
}

@Composable
fun FilterRuleDialog(
  open: Boolean,
  onCancel: () -> Unit,
  onConfirm: (FilterRuleInput) -> Unit,
) {
  if (!open) return
  val colors = LocalNg2nColors.current
  BackHandler(enabled = true, onBack = onCancel)

  var kind by remember { mutableStateOf(FilterRuleKind.KEYWORD) }
  var value by remember { mutableStateOf("") }
  var regex by remember { mutableStateOf(false) }
  var submitted by remember { mutableStateOf(false) }

  var started by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { started = true }
  val scrim by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(Motion.DURATION_QUICK, easing = Motion.easeStandard),
    label = "filter-dialog-scrim",
  )
  val pop by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(Motion.DURATION_BASE, easing = Motion.easeStandard),
    label = "filter-dialog-pop",
  )

  val regexOn = regex && kind == FilterRuleKind.KEYWORD
  val error = validateFilterRule(FilterRuleInput(kind = kind, value = value, regex = regexOn))

  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(Unit) { focus.requestFocus() }

  val confirm = {
    submitted = true
    if (error == null) {
      keyboard?.hide()
      onConfirm(FilterRuleInput(kind = kind, value = value, regex = regexOn))
    }
  }

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(
      Modifier
        .matchParentSize()
        .drawBehind { drawRect(colors.scrim, alpha = scrim) }
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClickLabel = "关闭对话框",
          onClick = onCancel,
        )
        .semantics { contentDescription = "关闭对话框" },
    )
    Column(
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
        )
        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = Spacing.row),
    ) {
      Text(
        text = "新增屏蔽规则",
        style = TextStyle(
          fontSize = Typo.dialogTitle.size,
          lineHeight = Typo.dialogTitle.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg,
        ),
      )

      Row(
        modifier = Modifier.padding(top = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        for (item in KINDS) {
          val on = kind == item
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(Radius.sm))
              .background(if (on) colors.primary else colors.surface2)
              .border(1.dp, if (on) colors.primary else colors.divider, RoundedCornerShape(Radius.sm))
              .clickable(onClickLabel = "按${item.label}屏蔽") { kind = item }
              .padding(vertical = 6.dp, horizontal = 14.dp),
          ) {
            Text(
              text = item.label,
              style = TextStyle(
                fontSize = Typo.listMeta.size,
                lineHeight = Typo.listMeta.lineHeight,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) colors.onPrimary else colors.fg2,
              ),
            )
          }
        }
      }

      CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
          handleColor = colors.primary,
          backgroundColor = colors.primary.copy(alpha = 0.3f),
        ),
      ) {
        Box(Modifier.padding(top = Spacing.lg)) {
          BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(
              fontSize = Typo.drawerItem.size,
              lineHeight = Typo.drawerItem.lineHeight,
              color = colors.fg,
            ),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier
              .fillMaxWidth()
              .focusRequester(focus)
              .drawBehind {
                val y = size.height + 7.dp.toPx()
                drawLine(colors.primary, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
              }
              .padding(bottom = 9.dp, start = 2.dp, end = 2.dp),
          )
          if (value.isEmpty()) {
            Text(
              text = placeholderOf(kind),
              modifier = Modifier.padding(start = 2.dp),
              style = TextStyle(
                fontSize = Typo.drawerItem.size,
                lineHeight = Typo.drawerItem.lineHeight,
                color = colors.meta,
              ),
            )
          }
        }
      }

      val showError = submitted && error != null
      Text(
        text = if (showError) error else hintOf(kind),
        modifier = Modifier.padding(top = 7.dp),
        style = TextStyle(
          fontSize = Typo.meta.size,
          lineHeight = Typo.meta.lineHeight,
          color = if (showError) colors.danger else colors.meta,
        ),
      )

      if (kind == FilterRuleKind.KEYWORD) {
        Row(
          modifier = Modifier
            .padding(top = Spacing.md)
            .clip(RoundedCornerShape(Radius.xs))
            .clickable(onClickLabel = if (regexOn) "关闭正则匹配" else "按正则匹配") { regex = !regex }
            .padding(vertical = 4.dp, horizontal = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
          AppIcon(
            icon = if (regexOn) Ng2nIcon.CHECK_BOX else Ng2nIcon.CHECK_BOX_OUTLINE_BLANK,
            tint = if (regexOn) colors.primary else colors.fg2,
            size = 22.dp,
          )
          Text(
            text = "按正则匹配",
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = colors.fg2),
          )
        }
      }

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
            .background(colors.primary)
            .clickable(onClick = confirm)
            .padding(horizontal = Spacing.xl),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "保存",
            style = TextStyle(
              fontSize = Typo.dialogAction.size,
              fontWeight = FontWeight.SemiBold,
              color = colors.onPrimary,
            ),
          )
        }
      }
    }
  }
}
