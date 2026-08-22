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
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.diagnostics.DiagnosticLogStore
import com.chasel.ng2n.data.history.HistoryRepository
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

  // ---- 票 17c 追加:设置树要读历史/缓存的计数,实验室页要读链路诊断 ----
  fun history(): HistoryRepository
  fun topicCache(): TopicCacheRepository
  fun diagnostics(): DiagnosticLogStore
  fun ngaClient(): NgaClient
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
  val diagnostics: DiagnosticLogStore = entryPoint.diagnostics()
  val ngaClient: NgaClient = entryPoint.ngaClient()
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
