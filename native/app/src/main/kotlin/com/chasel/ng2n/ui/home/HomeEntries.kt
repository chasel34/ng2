package com.chasel.ng2n.ui.home

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.board.BoardScreen
import com.chasel.ng2n.ui.board.HotTopicsScreen
import com.chasel.ng2n.ui.board.RecommendScreen
import com.chasel.ng2n.ui.board.SubBoardsScreen
import com.chasel.ng2n.ui.common.PlaceholderScreen
import com.chasel.ng2n.ui.nav.AboutKey
import com.chasel.ng2n.ui.nav.BoardFace
import com.chasel.ng2n.ui.nav.BoardKey
import com.chasel.ng2n.ui.nav.FavoriteFoldersKey
import com.chasel.ng2n.ui.nav.FavoritesKey
import com.chasel.ng2n.ui.nav.FiltersKey
import com.chasel.ng2n.ui.Home
import com.chasel.ng2n.ui.accounts.AccountsViewModel
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.NotificationsKey
import com.chasel.ng2n.ui.nav.SearchKey
import com.chasel.ng2n.ui.nav.SettingsKey
import com.chasel.ng2n.ui.nav.SubBoardsKey
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.UserKey
import com.chasel.ng2n.ui.nav.UserPostsKey
import com.chasel.ng2n.ui.nav.WebKey

/**
 * 票 16 的导航条目 —— 首页 / 版块面 / 抽屉宿主。
 *
 * 顺带**把别人的键都注册上占位屏**:`entryProvider` 找不到某个键的条目时 Nav3 直接抛,
 * 而抽屉里十来个入口指向的屏幕归票 17、主题详情归票 13、登录与多账号归票 15。
 * 那几张票落地时把对应的 `entry<…>` 换掉即可(**只删这一行,不动键的签名** ——
 * 键进了 back stack 的序列化形态)。
 *
 * 接收者类型是 `EntryProviderScope<NavKey>`(Nav3 1.1.6 的名字;票面写的
 * `EntryProviderBuilder` 是更早版本的叫法,见票 16 Comments)。
 */
fun EntryProviderScope<NavKey>.homeEntries(
  nav: Navigator,
  accounts: AccountsViewModel,
  onOpenDevMenu: () -> Unit,
) {
  entry<Home> { HomeScreen(nav = nav, accounts = accounts, onOpenDevMenu = onOpenDevMenu) }

  entry<BoardKey> { key ->
    // RN 版是三个路由(`/board/:id`、`/board/hot`、`/board/recommend`),
    // 这里合成一个键的三档 —— 它们共用同一套参数(id / kind / name),分成三个键
    // 只会让「从热帖点回版块」这类跳转多写三份参数映射
    when (key.face) {
      BoardFace.LIST -> BoardScreen(key = key, nav = nav)
      BoardFace.HOT -> HotTopicsScreen(key = key, nav = nav)
      BoardFace.RECOMMEND -> RecommendScreen(key = key, nav = nav)
    }
  }

  entry<SubBoardsKey> { key -> SubBoardsScreen(key = key, nav = nav) }

  // ---------------------------------------------------------------- 别人的键(占位)

  // 票 13:主题详情
  entry<TopicKey> { PlaceholderScreen("主题详情", "票 13", nav::pop) }

  // 票 15 的 Login / Accounts 真屏在 `ui/Ng2nApp.kt`(它们要共享 NavDisplay 外面那一份 AccountsViewModel)

  // 票 17:其余屏幕
  // 搜索 / 收藏 / 收藏夹管理 / 历史 / 缓存 / 通知 六个键归票 17a,真屏在
  // `ui/lists/ListEntries.kt`(挂在 `ui/Ng2nApp.kt` 的 entryProvider 上)
  entry<SearchKey> { PlaceholderScreen("搜索", "票 17", nav::pop) }
  entry<FavoritesKey> { PlaceholderScreen("收藏夹", "票 17", nav::pop) }
  entry<FavoriteFoldersKey> { PlaceholderScreen("收藏夹管理", "票 17", nav::pop) }
  entry<FiltersKey> { PlaceholderScreen("屏蔽规则", "票 17", nav::pop) }
  entry<NotificationsKey> { PlaceholderScreen("最近被喷", "票 17", nav::pop) }
  entry<UserKey> { PlaceholderScreen("用户资料", "票 17", nav::pop) }
  entry<UserPostsKey> { key ->
    PlaceholderScreen(
      title = if (key.kind == com.chasel.ng2n.ui.nav.UserPostKind.TOPICS) "我的主题" else "我的回复",
      owner = "票 17",
      onBack = nav::pop,
    )
  }
  entry<SettingsKey> { PlaceholderScreen("设置", "票 17", nav::pop) }
  entry<AboutKey> { PlaceholderScreen("关于", "票 17", nav::pop) }
  entry<WebKey> { key -> PlaceholderScreen(key.title ?: "网页版", "票 17", nav::pop) }
}
