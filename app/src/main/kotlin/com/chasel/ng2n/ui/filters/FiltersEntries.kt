package com.chasel.ng2n.ui.filters

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.nav.FiltersKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.user.UserProfileScreen
import com.chasel.ng2n.ui.user.UserPostsScreen
import com.chasel.ng2n.ui.nav.UserKey
import com.chasel.ng2n.ui.nav.UserPostsKey

fun EntryProviderScope<NavKey>.filtersAndUserEntries(nav: Navigator) {
  entry<FiltersKey> { FiltersScreen(nav = nav) }
  entry<UserKey> { key -> UserProfileScreen(key = key, nav = nav) }
  entry<UserPostsKey> { key -> UserPostsScreen(key = key, nav = nav) }
}
