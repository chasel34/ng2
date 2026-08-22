package com.chasel.ng2n.ui.lists

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.nav.CachesKey
import com.chasel.ng2n.ui.nav.HistoryKey
import com.chasel.ng2n.ui.nav.Navigator

/**
 * 票 17a 的导航条目 —— 搜索 / 收藏 / 收藏夹管理 / 历史 / 缓存管理 / 通知 六个键。
 *
 * 五组屏收在同一个包(`ui/lists`)里:它们共用副标题条、头像占位、每分钟走一格的
 * 时钟那几样零件(`ListCommon.kt`),而各自的数据源分别在
 * `data/search`、`data/favorites`、`data/history`、`data/cache`、`data/notifications`。
 *
 * 接收者类型是 `EntryProviderScope<NavKey>`(Nav3 1.1.6 的名字,见票 16 Comments)。
 */
fun EntryProviderScope<NavKey>.listEntries(nav: Navigator) {
  entry<HistoryKey> { HistoryScreen(nav = nav) }
  entry<CachesKey> { CachesScreen(nav = nav) }
}
