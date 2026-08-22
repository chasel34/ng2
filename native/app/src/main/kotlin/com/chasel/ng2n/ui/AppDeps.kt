package com.chasel.ng2n.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.board.BoardFavoriteRepository
import com.chasel.ng2n.data.board.BoardTreeRepository
import com.chasel.ng2n.data.board.CheckInRepository
import com.chasel.ng2n.data.board.HotTopicsRepository
import com.chasel.ng2n.data.board.SubBoardRepository
import com.chasel.ng2n.data.board.TopicListRepository
import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.favorites.TopicFavoriteRepository
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.data.search.SearchRepository
import com.chasel.ng2n.data.notifications.NotificationPoller
import com.chasel.ng2n.data.settings.SettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 屏幕拿仓库的口子。
 *
 * ## 为什么不是 ViewModel
 *
 * 这一版的仓库全是 `@Singleton` 且自带 `StateFlow` —— 数据本来就活在进程级
 * (对应 RN 侧那个全局 query cache:从版块进主题再返回,列表与滚动位置都还在;
 * 子版块屏更是直接读版块页已经拉过的第一页,ADR-0002「能少打就少打」)。
 * 再套一层 per-screen ViewModel 只会把同一份数据复制一遍,还要给 Nav3 配
 * `ViewModelStoreNavEntryDecorator` 才不会串屏。屏幕这一层要的是「订阅 + 发起动作」,
 * 这个口子就够。
 *
 * 做法与票 12 的 `rememberImagePipeline()` 同源(Hilt EntryPoint + `remember`),
 * 取的都是同一批单例。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppDepsEntryPoint {
  fun boardTree(): BoardTreeRepository
  fun boardFavorites(): BoardFavoriteRepository
  fun topicLists(): TopicListRepository
  fun hotTopics(): HotTopicsRepository
  fun subBoards(): SubBoardRepository
  fun checkIn(): CheckInRepository
  fun notifications(): NotificationPoller
  fun accounts(): AccountStore
  fun settings(): SettingsStore

  // ---- 票 17a 追加(搜索 / 收藏 / 历史 / 缓存管理)----
  fun history(): HistoryRepository
  fun topicCache(): TopicCacheRepository
  fun topicFavorites(): TopicFavoriteRepository
  fun search(): SearchRepository
}

class AppDeps(entryPoint: AppDepsEntryPoint) {
  val boardTree: BoardTreeRepository = entryPoint.boardTree()
  val boardFavorites: BoardFavoriteRepository = entryPoint.boardFavorites()
  val topicLists: TopicListRepository = entryPoint.topicLists()
  val hotTopics: HotTopicsRepository = entryPoint.hotTopics()
  val subBoards: SubBoardRepository = entryPoint.subBoards()
  val checkIn: CheckInRepository = entryPoint.checkIn()
  val notifications: NotificationPoller = entryPoint.notifications()
  val accounts: AccountStore = entryPoint.accounts()
  val settings: SettingsStore = entryPoint.settings()
  val history: HistoryRepository = entryPoint.history()
  val topicCache: TopicCacheRepository = entryPoint.topicCache()
  val topicFavorites: TopicFavoriteRepository = entryPoint.topicFavorites()
  val search: SearchRepository = entryPoint.search()
}

@Composable
fun rememberAppDeps(): AppDeps {
  val context: Context = LocalContext.current
  return remember(context) {
    AppDeps(
      EntryPointAccessors.fromApplication(
        context.applicationContext,
        AppDepsEntryPoint::class.java,
      ),
    )
  }
}
