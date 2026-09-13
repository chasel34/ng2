package com.chasel.ng2n.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.bookmarks.BookmarkRepository
import com.chasel.ng2n.data.board.BoardFavoriteRepository
import com.chasel.ng2n.data.board.BoardTreeRepository
import com.chasel.ng2n.data.board.CheckInRepository
import com.chasel.ng2n.data.board.HotTopicsRepository
import com.chasel.ng2n.data.board.SubBoardRepository
import com.chasel.ng2n.data.board.TopicListRepository
import com.chasel.ng2n.data.filters.FilterRepository
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.diagnostics.DiagnosticLogStore
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.data.favorites.TopicFavoriteRepository
import com.chasel.ng2n.data.search.SearchRepository
import com.chasel.ng2n.data.notifications.NotificationPoller
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.user.UserPostsRepository
import com.chasel.ng2n.data.user.UserProfileRepository
import com.chasel.ng2n.di.IoScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

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

  fun filters(): FilterRepository
  fun userProfiles(): UserProfileRepository
  fun userPosts(): UserPostsRepository
  fun history(): HistoryRepository
  fun topicCache(): TopicCacheRepository
  fun diagnostics(): DiagnosticLogStore
  fun ngaClient(): NgaClient
  fun topicFavorites(): TopicFavoriteRepository
  fun search(): SearchRepository
  fun bookmarks(): BookmarkRepository

  @IoScope
  fun ioScope(): CoroutineScope
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
  val filters: FilterRepository = entryPoint.filters()
  val userProfiles: UserProfileRepository = entryPoint.userProfiles()
  val userPosts: UserPostsRepository = entryPoint.userPosts()
  val history: HistoryRepository = entryPoint.history()
  val topicCache: TopicCacheRepository = entryPoint.topicCache()
  val diagnostics: DiagnosticLogStore = entryPoint.diagnostics()
  val ngaClient: NgaClient = entryPoint.ngaClient()
  val topicFavorites: TopicFavoriteRepository = entryPoint.topicFavorites()
  val search: SearchRepository = entryPoint.search()
  val bookmarks: BookmarkRepository = entryPoint.bookmarks()

  /** 应用级作用域：撤销这类不能随页面退场取消的工作挂在这里。 */
  val ioScope: CoroutineScope = entryPoint.ioScope()
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
