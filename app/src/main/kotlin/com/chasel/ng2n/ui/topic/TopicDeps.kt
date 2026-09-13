package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.nav.TopicKey
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.bookmarks.BookmarkRepository
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

@Singleton
class TopicDeps @Inject constructor(
  val client: NgaClient,
  val repository: TopicRepository,
  val history: HistoryRepository,
  val bookmarks: BookmarkRepository,
  val topicCache: TopicCacheRepository,
  val settings: SettingsStore,
  val credentials: CredentialSource,
  val attachmentUrls: AttachmentUrls,
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
