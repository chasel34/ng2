package com.chasel.ng2n.ui.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

/**
 * 开发者入口。
 *
 * 票 11 / 12 / 15 各自留了一个「模拟器手验屏」,票 18 的功能验收还要用它们
 * (BBCode 渲染覆盖、图片管线、登录流程),所以**不删 demo 屏本身**,
 * 只把入口从首页收进这里 —— 首页是真首页了,不该再挂三个 demo 按钮。
 *
 * 进入方式:抽屉「关于」**长按**。不做成常驻入口是因为它不是给用户的。
 *
 * TODO(票 17):由那张票决定这个菜单是留(挪进「实验室」)还是删。
 */
@Serializable
data object DevMenuKey : NavKey

/** uiautomator 找入口用的锚点(票 18 的手验脚本认它)。 */
const val DEV_MENU_TAG: String = "ng2n-dev-menu"

@Composable
fun DevMenuScreen(
  onBack: () -> Unit,
  entries: List<Pair<String, () -> Unit>>,
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
        text = "票 11 / 12 的模拟器手验屏。票 18 功能验收要用,不删;票 17 决定去留。",
        modifier = Modifier.padding(Spacing.lg),
        style = TextStyle(
          fontSize = Typo.notice.size,
          lineHeight = Typo.notice.lineHeight,
          color = colors.fg2,
        ),
      )
      entries.forEach { (label, onClick) ->
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .drawBehind {
              val y = size.height - 1f
              drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
            }
            .padding(horizontal = Spacing.xl),
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
          Text(
            text = label,
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
