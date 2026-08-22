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

/**
 * 抽屉正文 —— 直译 RN 侧 `src/ui/app-drawer.tsx`。
 *
 * **14 项入口,顺序与图标照抄设计稿**;每一条要么跳一个屏,要么关掉抽屉、由宿主页面
 * 弹对应的对话框,要么就地执行(签到)。
 *
 * 顶部的账号头是一个**插槽**:票 15(登录与多账号)产出 `ui/accounts/AccountHeader.kt`
 * (滑动循环切号、点头像进资料、点账号名进账号管理),主控合并后把它接到这里。
 * 本票给的是 [GuestAccountHeader] 占位 —— 游客态那一档本来就长这样,不算白写。
 */
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
  // 签到是设计稿缺失页面(spec §1:抽屉「登录账号」下加一行),按现有条目的设计语言延伸
  DrawerEntry(DrawerEntryKey.CHECK_IN, Ng2nIcon.WORKSPACE_PREMIUM, "每日签到"),
  DrawerEntry(DrawerEntryKey.ADD_BOARD, Ng2nIcon.LIBRARY_ADD, "添加版面 ID"),
  DrawerEntry(DrawerEntryKey.FROM_URL, Ng2nIcon.ARROW_FORWARD, "由 URL 读取"),
  // 收藏夹(收藏的主题)与收藏夹管理(新建/改名/设默认)是两屏,顶栏 kebab 撤掉后
  // 前者只剩这一个入口,两条挨着放
  DrawerEntry(DrawerEntryKey.FAVORITES, Ng2nIcon.BOOKMARK, "收藏夹"),
  DrawerEntry(DrawerEntryKey.FAVORITE_FOLDERS, Ng2nIcon.FOLDER_SPECIAL, "收藏夹管理"),
  DrawerEntry(DrawerEntryKey.CLEAR_FAVORITES, Ng2nIcon.WARNING, "清空我的收藏"),
  // 我的主题/我的回复是同一个屏,只差一个 kind
  DrawerEntry(DrawerEntryKey.MY_TOPICS, Ng2nIcon.ARTICLE, "我的主题"),
  DrawerEntry(DrawerEntryKey.MY_REPLIES, Ng2nIcon.REPLY, "我的回复"),
  // 设计稿把「我的缓存」放在首页菜单里,抽屉这条是顺手的第二入口——
  // 抽屉在每一屏都拉得出来,不必先退回首页再开菜单
  DrawerEntry(DrawerEntryKey.CACHES, Ng2nIcon.CACHED, "我的缓存"),
  // 短消息整块不在 v1(spec §1),入口留着走「本版本未开放」
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
  /** 票 15 的 `AccountHeader` 接到这里;缺省是游客态那一档占位 */
  accountHeader: @Composable () -> Unit = {},
  /** 签到那行右侧的状态灰字;游客态给 null(RN 侧同:没登录不显示) */
  checkInStatus: String? = null,
  /** 「最近被喷」的未读角标 */
  unread: Int = 0,
  /** 「关于」长按 → 开发者入口(**TODO 票 17 决定去留**) */
  onAboutLongPress: () -> Unit = {},
) {
  val colors = LocalNg2nColors.current
  Column(
    modifier
      .fillMaxSize()
      .background(colors.surface)
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

/** 未读角标照设计稿短消息屏:18 高胶囊、红底白字,顶到行尾。 */
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

/**
 * 抽屉顶部账号头的**占位**(游客态那一档)。
 *
 * TODO(票 15):换成 `ui/accounts/AccountHeader.kt` —— 登录态的头像、左右滑动循环
 * 切号、点头像进资料页、点账号名进账号管理页,都归那张票。
 */
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
