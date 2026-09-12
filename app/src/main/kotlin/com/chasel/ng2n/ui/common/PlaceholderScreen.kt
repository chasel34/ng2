package com.chasel.ng2n.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors

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
