package com.chasel.ng2n.ui.filters

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.nav.FiltersKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.user.UserProfileScreen
import com.chasel.ng2n.ui.user.UserPostsScreen
import com.chasel.ng2n.ui.nav.UserKey
import com.chasel.ng2n.ui.nav.UserPostsKey

/**
 * 票 17b 的导航条目 —— 屏蔽规则 / 用户资料 / 我的主题·我的回复。
 *
 * 三个键原本在 `ui/home/HomeEntries.kt` 里注册成占位屏(票 16 为了让抽屉的入口
 * 点得动),那三行在本票里删掉,换成这里的真屏。**键的签名一个字没动** ——
 * 它进了 back stack 的序列化形态。
 */
fun EntryProviderScope<NavKey>.filtersAndUserEntries(nav: Navigator) {
  entry<FiltersKey> { FiltersScreen(nav = nav) }
  entry<UserKey> { key -> UserProfileScreen(key = key, nav = nav) }
  entry<UserPostsKey> { key -> UserPostsScreen(key = key, nav = nav) }
}
