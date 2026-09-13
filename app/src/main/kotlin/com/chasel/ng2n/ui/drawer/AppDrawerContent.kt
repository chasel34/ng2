package com.chasel.ng2n.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.TopbarOverlay
import com.chasel.ng2n.ui.theme.Typo

enum class DrawerEntryKey {
  LOGIN,
  CHECK_IN,
  ADD_BOARD,
  FROM_URL,
  FAVORITES,
  FAVORITE_FOLDERS,
  CLEAR_FAVORITES,
  MY_TOPICS,
  MY_REPLIES,
  CACHES,
  BOOKMARKS,
  MESSAGES,
  NOTIFICATIONS,
  SETTINGS,
  ABOUT,
}

private data class DrawerEntry(
  val key: DrawerEntryKey,
  val icon: Ng2nIcon,
  val label: String,
)

private val ENTRIES = listOf(
  DrawerEntry(DrawerEntryKey.LOGIN, Ng2nIcon.PERSON_ADD, "登录账号"),
  DrawerEntry(DrawerEntryKey.CHECK_IN, Ng2nIcon.WORKSPACE_PREMIUM, "每日签到"),
  DrawerEntry(DrawerEntryKey.ADD_BOARD, Ng2nIcon.LIBRARY_ADD, "添加版面 ID"),
  DrawerEntry(DrawerEntryKey.FROM_URL, Ng2nIcon.ARROW_FORWARD, "由 URL 读取"),
  DrawerEntry(DrawerEntryKey.FAVORITES, Ng2nIcon.BOOKMARK, "收藏夹"),
  DrawerEntry(DrawerEntryKey.FAVORITE_FOLDERS, Ng2nIcon.FOLDER_SPECIAL, "收藏夹管理"),
  DrawerEntry(DrawerEntryKey.CLEAR_FAVORITES, Ng2nIcon.WARNING, "清空我的收藏"),
  DrawerEntry(DrawerEntryKey.MY_TOPICS, Ng2nIcon.ARTICLE, "我的主题"),
  DrawerEntry(DrawerEntryKey.MY_REPLIES, Ng2nIcon.REPLY, "我的回复"),
  DrawerEntry(DrawerEntryKey.CACHES, Ng2nIcon.CACHED, "我的缓存"),
  DrawerEntry(DrawerEntryKey.BOOKMARKS, Ng2nIcon.BOOKMARK_ADDED, "书签"),
  DrawerEntry(DrawerEntryKey.MESSAGES, Ng2nIcon.SMS, "短消息"),
  DrawerEntry(DrawerEntryKey.NOTIFICATIONS, Ng2nIcon.NOTIFICATIONS_ACTIVE, "最近被喷"),
  DrawerEntry(DrawerEntryKey.SETTINGS, Ng2nIcon.SETTINGS, "设置"),
  DrawerEntry(DrawerEntryKey.ABOUT, Ng2nIcon.INFO, "关于"),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerContent(
  onEntry: (DrawerEntryKey) -> Unit,
  modifier: Modifier = Modifier,
  accountHeader: @Composable () -> Unit = {},
  checkInStatus: String? = null,
  unread: Int = 0,
  onAboutLongPress: () -> Unit = {},
) {
  val colors = LocalNg2nColors.current
  Column(
    modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
  ) {
    accountHeader()

    Text(
      text = "论坛功能",
      modifier = Modifier.padding(
        top = Spacing.lg,
        start = Spacing.xl,
        end = Spacing.xl,
        bottom = 6.dp,
      ),
      style = TextStyle(
        fontSize = Typo.caption.size,
        lineHeight = Typo.caption.lineHeight,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        color = colors.primary,
      ),
    )

    ENTRIES.forEach { entry ->
      val rowModifier = if (entry.key == DrawerEntryKey.ABOUT) {
        Modifier.combinedClickable(
          onClick = { onEntry(entry.key) },
          onLongClick = onAboutLongPress,
          onClickLabel = entry.label,
        )
      } else {
        Modifier.clickable(onClickLabel = entry.label) { onEntry(entry.key) }
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .then(rowModifier)
          .padding(horizontal = Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        AppIcon(icon = entry.icon, tint = colors.fg2, size = 21.dp)
        Text(
          text = entry.label,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = TextStyle(
            fontSize = Typo.drawerItem.size,
            lineHeight = Typo.drawerItem.lineHeight,
            color = colors.fg,
          ),
        )
        if (entry.key == DrawerEntryKey.CHECK_IN && checkInStatus != null) {
          Spacer(Modifier.weight(1f))
          Text(
            text = checkInStatus,
            maxLines = 1,
            style = TextStyle(
              fontSize = Typo.meta.size,
              lineHeight = Typo.meta.lineHeight,
              color = colors.meta,
            ),
          )
        }
        if (entry.key == DrawerEntryKey.NOTIFICATIONS && unread > 0) {
          Spacer(Modifier.weight(1f))
          UnreadBadge(unread)
        }
      }
    }
    Spacer(Modifier.height(Spacing.xl))
  }
}

@Composable
private fun UnreadBadge(count: Int) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = Modifier
      .widthIn(min = 18.dp)
      .height(18.dp)
      .clip(RoundedCornerShape(Radius.sm))
      .background(colors.danger)
      .padding(horizontal = 5.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = if (count > 99) "99+" else count.toString(),
      style = TextStyle(
        fontSize = Typo.unreadBadge.size,
        lineHeight = Typo.unreadBadge.lineHeight,
        fontWeight = FontWeight.Bold,
        color = colors.onPrimary,
      ),
    )
  }
}

@Composable
fun GuestAccountHeader(onLogin: () -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  Column(
    modifier
      .fillMaxWidth()
      .background(colors.primary)
      .padding(top = statusBar + 22.dp, bottom = 18.dp)
      .padding(horizontal = Spacing.xl),
  ) {
    Box(
      modifier = Modifier
        .size(64.dp)
        .clip(RoundedCornerShape(22.dp))
        .background(TopbarOverlay),
      contentAlignment = Alignment.Center,
    ) {
      AppIcon(icon = Ng2nIcon.PERSON_ADD, tint = colors.onPrimary, size = 26.dp)
    }
    Text(
      text = "未登录 · 登录多个账号可少跳系统浏览器",
      modifier = Modifier.padding(top = Spacing.row),
      style = TextStyle(
        fontSize = Typo.listSubtitle.size,
        lineHeight = Typo.listSubtitle.lineHeight,
        color = colors.onPrimary.copy(alpha = 0.8f),
      ),
    )
    Text(
      text = "点此登录账号",
      modifier = Modifier
        .padding(top = 3.dp)
        .clickable(onClickLabel = "登录账号", onClick = onLogin),
      style = TextStyle(
        fontSize = Typo.tab.size,
        lineHeight = Typo.tab.lineHeight,
        fontWeight = FontWeight.SemiBold,
        color = colors.onPrimary,
      ),
    )
  }
}
