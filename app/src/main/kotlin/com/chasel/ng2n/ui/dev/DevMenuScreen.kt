package com.chasel.ng2n.ui.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.serialization.Serializable

@Serializable
data object DevMenuKey : NavKey

const val DEV_MENU_TAG: String = "ng2n-dev-menu"

@Composable
fun DevMenuScreen(
  onBack: () -> Unit,
  entries: List<DevMenuEntry>,
  modifier: Modifier = Modifier,
) {
  val colors = LocalNg2nColors.current
  Column(
    modifier
      .fillMaxSize()
      .background(colors.bg)
      .semantics { contentDescription = DEV_MENU_TAG },
  ) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = onBack,
      )
      TopBarTitle(text = "开发者入口", variant = TopBarTitleVariant.SUB)
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
      Text(
        text = "票 11 / 12 / 15 的模拟器手验屏。票 18 功能验收要用,不删;票 17 决定去留。",
        modifier = Modifier.padding(Spacing.lg),
        style = TextStyle(
          fontSize = Typo.notice.size,
          lineHeight = Typo.notice.lineHeight,
          color = colors.fg2,
        ),
      )
      entries.forEach { item ->
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClickLabel = item.label, onClick = item.onClick)
            .semantics { contentDescription = item.tag }
            .drawBehind {
              val y = size.height - 1f
              drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
            }
            .padding(horizontal = Spacing.xl),
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = item.label,
            style = TextStyle(
              fontSize = Typo.drawerItem.size,
              lineHeight = Typo.drawerItem.lineHeight,
              color = colors.fg,
            ),
          )
        }
      }
    }
  }
}

data class DevMenuEntry(val label: String, val tag: String, val onClick: () -> Unit)
