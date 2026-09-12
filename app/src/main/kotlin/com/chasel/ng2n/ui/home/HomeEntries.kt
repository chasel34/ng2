package com.chasel.ng2n.ui.home

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.board.BoardScreen
import com.chasel.ng2n.ui.board.HotTopicsScreen
import com.chasel.ng2n.ui.board.RecommendScreen
import com.chasel.ng2n.ui.board.SubBoardsScreen
import com.chasel.ng2n.ui.nav.BoardFace
import com.chasel.ng2n.ui.nav.BoardKey
import com.chasel.ng2n.ui.Home
import com.chasel.ng2n.ui.accounts.AccountsViewModel
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.SubBoardsKey

fun EntryProviderScope<NavKey>.homeEntries(
  nav: Navigator,
  accounts: AccountsViewModel,
  onOpenDevMenu: () -> Unit,
) {
  entry<Home> { HomeScreen(nav = nav, accounts = accounts, onOpenDevMenu = onOpenDevMenu) }

  entry<BoardKey> { key ->
    when (key.face) {
      BoardFace.LIST -> BoardScreen(key = key, nav = nav)
      BoardFace.HOT -> HotTopicsScreen(key = key, nav = nav)
      BoardFace.RECOMMEND -> RecommendScreen(key = key, nav = nav)
    }
  }

  entry<SubBoardsKey> { key -> SubBoardsScreen(key = key, nav = nav) }

}
