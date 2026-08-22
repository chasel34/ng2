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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.account.NgaAccount
import com.chasel.ng2n.data.account.formatCookieExpiry
import com.chasel.ng2n.ui.image.BackIcon

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
 * 视觉这一版按 Material3 的默认配色摆,**不自造 token**:完整设计 token 是票 17 的活。
 */
@Composable
fun AccountsScreen(
  viewModel: AccountsViewModel,
  onBack: () -> Unit,
  onAddAccount: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
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
      .background(MaterialTheme.colorScheme.background)
      .semantics { contentDescription = ACCOUNTS_SCREEN_TAG },
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
        BackIcon(tint = MaterialTheme.colorScheme.onBackground)
      }
      Text(
        text = "账号管理",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp),
      )
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

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .clip(RoundedCornerShape(14.dp))
          .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
          .clickable(onClick = onAddAccount)
          .semantics { contentDescription = "添加账号" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        PersonAddIcon(tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(9.dp))
        Text(
          text = "添加账号",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      Spacer(Modifier.height(18.dp))
      Text(
        text = "登录多个账号可减少跳转系统浏览器的概率；抽屉头部左右滑动即可快速切换当前账号。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
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
  val borderColor =
    if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surface)
      .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
      .clickable(onClick = onSwitch)
      .semantics { contentDescription = "切换到 ${account.name}" }
      .padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    Box(
      modifier = Modifier
        .size(46.dp)
        .clip(RoundedCornerShape(15.dp))
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = nameAbbrev(account.name, 2),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = account.name,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "UID ${account.uid} · cookie $expiry",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RadioIcon(
      tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
      checked = isCurrent,
    )
    IconButton(
      onClick = onLogout,
      modifier = Modifier
        .size(32.dp)
        .semantics { contentDescription = "退出 ${account.name}" },
    ) {
      LogoutIcon(tint = MaterialTheme.colorScheme.error)
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
