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

/** uiautomator 找账号管理屏的锚点。 */
const val ACCOUNTS_SCREEN_TAG: String = "ng2n-accounts-screen"

/**
 * 多账号管理屏 —— `src/app/accounts.tsx` 的移植(设计稿 isAccounts 屏)。
 *
 * 一条账号 = 头像 + 名字 + 「UID xxx · cookie N 天后过期」+ 当前标记 + 退出按钮;
 * 点整条切号,点右侧小门标退出。「30 天后过期」是**客户端假设值**:
 * `Set-Cookie` 的真实 expires 拿不到,按 passport 的 30 天惯例从登录时刻推算,
 * 只作展示、不做任何强制(见 `data/account/Accounts.kt`)。
 *
 * 视觉走全 app 同一套(票 32 补的是票 15 留下的欠账):顶栏用 [TopBar] +
 * [TopBarButton] / [TopBarTitle],配色取 `LocalNg2nColors`、字号取 `Typo`。
 * 票 15 落地时设计 token 还没铺完,这一屏当时用的是 Material3 默认配色与就地拼的
 * `Row` 顶栏 —— 于是它成了唯一一屏换主题不跟着变、顶栏尺寸和别处对不齐、
 * 返回钮在无障碍树里没名字的屏(TalkBack 念不出,uiautomator 也点不到)。
 */
@Composable
fun AccountsScreen(
  viewModel: AccountsViewModel,
  onBack: () -> Unit,
  onAddAccount: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val colors = LocalNg2nColors.current
  // 过期天数只随进屏那一刻算一次:秒级刷新对「还剩 29 天」毫无意义
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
    // 状态栏安全区归 TopBar 自己撑(edge-to-edge),这里不再叠 windowInsetsPadding
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

      // 「添加账号」是**虚线**描边的空框(RN 侧 `borderStyle: 'dashed'` + `colors.track`)——
      // Compose 的 `Modifier.border` 只画实线,虚线要自己 `drawBehind` 一条带
      // `dashPathEffect` 的描边(票 48)
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
    // 头像方块按 **uid** 散列取色 + 白字(RN 侧 `accounts.tsx` 的
    // `avatarColorFor(account.uid)` + `onPrimary`)。票 32 当时选的
    // 「primaryContainer 底 + primary 字」是照收藏夹「默认」徽标抄的,语义不同:
    // 那是一个状态徽标,这是**身份**色 —— 登了多个账号时全屏一个色就分不出人了(票 48)
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
    // 原先是 Material3 的 IconButton(涟漪取 M3 色);换成与全 app 同款的圆形触控盒,
    // onClickLabel 让 TalkBack 的「双击以…」也念得出这是「退出」
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

/**
 * 账号头像里的缩写 —— `src/ui/initial.ts` 的 `nameAbbrev` 直译:
 * 拉丁名取前 [asciiCount] 个可见 ASCII(设计稿 `chasel43` → 抽屉「chas」、账号管理页「ch」),
 * CJK 名一个字就够宽,只取首字。
 *
 * 按**码点**切而不是按 `Char`:名字里可能有 emoji 或生僻字,按 UTF-16 码元切会劈出
 * 半个代理对,渲染成豆腐块(RN 版用 `Array.from` 是同一个理由)。
 */
internal fun nameAbbrev(name: String, asciiCount: Int): String {
  val chars = name.trim().codePoints().toArray()
  if (chars.isEmpty()) return "#"
  if (!isVisibleAscii(chars[0])) return String(chars, 0, 1)
  var count = 0
  while (count < chars.size && count < asciiCount && isVisibleAscii(chars[count])) count += 1
  return String(chars, 0, count)
}

/** RN 版的 `/^[\x21-\x7e]$/`:可见 ASCII(不含空格)。 */
private fun isVisibleAscii(codePoint: Int): Boolean = codePoint in 0x21..0x7e
