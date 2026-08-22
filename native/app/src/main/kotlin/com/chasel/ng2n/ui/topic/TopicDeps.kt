package com.chasel.ng2n.ui.topic

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.di.IoScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主题三屏(详情 / 回复链 / 未来的资料)要的全部单例,打成一个门面。
 *
 * 为什么不是 `@HiltViewModel`:Nav3 的条目作用域由
 * `rememberViewModelStoreNavEntryDecorator()` 提供,它给的是一个**裸**
 * `ViewModelStoreOwner`,不实现 `HasDefaultViewModelProviderFactory`,
 * 所以 `hiltViewModel()` 在这儿拿不到 Hilt 的工厂。而 ViewModel 又必须收
 * [TopicKey](路由参数),Hilt 的 `@AssistedInject` 也得走它的工厂。
 * 沿用票 12 `ImagePipeline` 立下的做法:**EntryPoint 取依赖 + 手写工厂**,
 * 一条路走到底,少一层会崩的间接。
 */
@Singleton
class TopicDeps @Inject constructor(
  val client: NgaClient,
  val repository: TopicRepository,
  val history: HistoryRepository,
  val topicCache: TopicCacheRepository,
  val settings: SettingsStore,
  val credentials: CredentialSource,
  val attachmentUrls: AttachmentUrls,
  /**
   * 全 app 一个的后台 scope。只用在一处:`onCleared` 里把最后一秒的阅读进度落盘 ——
   * 那时 `viewModelScope` 已经取消了。
   */
  @IoScope val scope: CoroutineScope,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TopicDepsEntryPoint {
  fun topicDeps(): TopicDeps
}

@Composable
fun rememberTopicDeps(): TopicDeps {
  val context: Context = LocalContext.current
  return remember(context) {
    EntryPointAccessors
      .fromApplication(context.applicationContext, TopicDepsEntryPoint::class.java)
      .topicDeps()
  }
}
