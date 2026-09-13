package com.chasel.ng2n.ui.lists

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.bookmarks.BookmarksScreen
import com.chasel.ng2n.ui.nav.BookmarksKey
import com.chasel.ng2n.ui.nav.CachesKey
import com.chasel.ng2n.ui.nav.FavoriteFoldersKey
import com.chasel.ng2n.ui.nav.FavoritesKey
import com.chasel.ng2n.ui.nav.HistoryKey
import com.chasel.ng2n.ui.nav.NotificationsKey
import com.chasel.ng2n.ui.nav.SearchKey
import com.chasel.ng2n.ui.nav.Navigator

fun EntryProviderScope<NavKey>.listEntries(nav: Navigator) {
  entry<HistoryKey> { HistoryScreen(nav = nav) }
  entry<CachesKey> { CachesScreen(nav = nav) }
  entry<BookmarksKey> { BookmarksScreen(nav = nav) }
  entry<FavoritesKey> { FavoritesScreen(nav = nav) }
  entry<FavoriteFoldersKey> { FavoriteFoldersScreen(nav = nav) }
  entry<SearchKey> { key -> SearchScreen(key = key, nav = nav) }
  entry<NotificationsKey> { NotificationsScreen(nav = nav) }
}
