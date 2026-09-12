package com.chasel.ng2n.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors

/**
 * 还没实现的屏幕。
 *
 * **存在的理由是 Nav3 会崩**:`entryProvider` 找不到某个键的条目时直接抛异常,
 * 而抽屉里十来个入口指向的屏幕归票 17。所以本票把每个键都注册上,屏幕本体
 * 由票 17 替换 —— 那时把 `homeEntries` 里对应的一行删掉即可。
 *
 * 顶栏是真的(能返回),正文是一句实话。
 */
@Composable
fun PlaceholderScreen(
  title: String,
  owner: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = LocalNg2nColors.current
  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = onBack,
      )
      TopBarTitle(text = title, variant = TopBarTitleVariant.SUB)
    }
    EmptyState(
      icon = Ng2nIcon.ARTICLE,
      text = "「$title」还没铺开\n这一屏归$owner",
    )
  }
}
