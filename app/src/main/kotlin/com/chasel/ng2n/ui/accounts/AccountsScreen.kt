package com.chasel.ng2n.ui.accounts

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.account.NgaAccount
import com.chasel.ng2n.data.account.formatCookieExpiry
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import com.chasel.ng2n.ui.theme.avatarColorFor

const val ACCOUNTS_SCREEN_TAG: String = "ng2n-accounts-screen"

@Composable
fun AccountsScreen(
  viewModel: AccountsViewModel,
  onBack: () -> Unit,
  onAddAccount: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val colors = LocalNg2nColors.current
  val now = remember { System.currentTimeMillis() }

  LaunchedEffect(viewModel) {
    viewModel.toasts.collect { message ->
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(colors.bg)
      .semantics { contentDescription = ACCOUNTS_SCREEN_TAG },
  ) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = onBack,
      )
      TopBarTitle(text = "账号管理", variant = TopBarTitleVariant.SUB)
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(horizontal = 12.dp)
        .padding(top = 14.dp, bottom = 24.dp),
    ) {
      for (account in state.accounts) {
        AccountRow(
          account = account,
          isCurrent = account.uid == state.currentUid,
          expiry = formatCookieExpiry(account.loginAt, now),
          onSwitch = { viewModel.switchTo(account.uid) },
          onLogout = { viewModel.logout(account.uid) },
        )
        Spacer(Modifier.height(10.dp))
      }

      val dash = with(LocalDensity.current) {
        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
      }
      val dashStroke = with(LocalDensity.current) { 1.5.dp.toPx() }
      val dashRadius = with(LocalDensity.current) { CornerRadius(Radius.lg.toPx(), Radius.lg.toPx()) }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .clip(RoundedCornerShape(Radius.lg))
          .drawBehind {
            drawRoundRect(
              color = colors.track,
              topLeft = Offset(dashStroke / 2f, dashStroke / 2f),
              size = Size(size.width - dashStroke, size.height - dashStroke),
              cornerRadius = dashRadius,
              style = Stroke(width = dashStroke, pathEffect = dash),
            )
          }
          .clickable(onClick = onAddAccount)
          .semantics { contentDescription = "添加账号" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        PersonAddIcon(tint = colors.primary)
        Spacer(Modifier.size(9.dp))
        Text(
          text = "添加账号",
          style = TextStyle(
            fontSize = Typo.drawerItem.size,
            lineHeight = Typo.drawerItem.lineHeight,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
          ),
        )
      }

      Spacer(Modifier.height(18.dp))
      Text(
        text = "登录多个账号可减少跳转系统浏览器的概率；抽屉头部左右滑动即可快速切换当前账号。",
        style = TextStyle(
          fontSize = Typo.note.size,
          lineHeight = Typo.note.lineHeight,
          color = colors.meta,
        ),
        modifier = Modifier.padding(horizontal = Spacing.xs),
      )
    }
  }
}

@Composable
private fun AccountRow(
  account: NgaAccount,
  isCurrent: Boolean,
  expiry: String,
  onSwitch: () -> Unit,
  onLogout: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val borderColor = if (isCurrent) colors.primary else colors.divider
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.lg))
      .background(colors.surface)
      .border(1.5.dp, borderColor, RoundedCornerShape(Radius.lg))
      .clickable(onClick = onSwitch)
      .semantics { contentDescription = "切换到 ${account.name}" }
      .padding(Spacing.row),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    Box(
      modifier = Modifier
        .size(46.dp)
        .clip(RoundedCornerShape(15.dp))
        .background(avatarColorFor(account.uid)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = nameAbbrev(account.name, 2),
        style = TextStyle(
          fontSize = Typo.avatarInitial.size,
          lineHeight = Typo.avatarInitial.lineHeight,
          fontWeight = FontWeight.Bold,
          color = colors.onPrimary,
        ),
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = account.name,
        style = TextStyle(
          fontSize = Typo.listTitle.size,
          lineHeight = Typo.listTitle.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "UID ${account.uid} · cookie $expiry",
        style = TextStyle(
          fontSize = Typo.listSubtitle.size,
          lineHeight = Typo.listSubtitle.lineHeight,
          color = colors.meta,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RadioIcon(tint = if (isCurrent) colors.primary else colors.meta, checked = isCurrent)
    Box(
      modifier = Modifier
        .size(32.dp)
        .clip(RoundedCornerShape(Radius.full))
        .clickable(onClickLabel = "退出 ${account.name}", onClick = onLogout)
        .semantics { contentDescription = "退出 ${account.name}" },
      contentAlignment = Alignment.Center,
    ) {
      LogoutIcon(tint = colors.danger)
    }
  }
}

internal fun nameAbbrev(name: String, asciiCount: Int): String {
  val chars = name.trim().codePoints().toArray()
  if (chars.isEmpty()) return "#"
  if (!isVisibleAscii(chars[0])) return String(chars, 0, 1)
  var count = 0
  while (count < chars.size && count < asciiCount && isVisibleAscii(chars[count])) count += 1
  return String(chars, 0, count)
}

private fun isVisibleAscii(codePoint: Int): Boolean = codePoint in 0x21..0x7e
