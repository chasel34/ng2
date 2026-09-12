package com.chasel.ng2n.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import com.chasel.ng2n.core.net.ComboCache
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.core.net.FetchDiagnostic
import com.chasel.ng2n.core.net.InMemoryComboCache
import com.chasel.ng2n.core.net.NetworkSettingsSource
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.TransportFactory
import com.chasel.ng2n.core.net.UserAgents
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import com.chasel.ng2n.data.diagnostics.DiagnosticLogStore
import com.chasel.ng2n.data.net.CurrentCredentialCache
import com.chasel.ng2n.data.net.NgaCookieJar
import com.chasel.ng2n.data.net.OkHttpTransportFactory
import com.chasel.ng2n.data.net.SettingsNetworkSource
import com.chasel.ng2n.data.net.SystemUserAgent
import com.chasel.ng2n.data.net.TopicCachePayloadReader
import com.chasel.ng2n.data.net.ngaHttpClientBuilder
import com.chasel.ng2n.data.net.toRecord
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

  @Provides
  @Singleton
  fun provideCurrentCredentialCache(): CurrentCredentialCache = CurrentCredentialCache()

  @Provides
  @Singleton
  fun provideOkHttpClient(credentials: CurrentCredentialCache): OkHttpClient =
    ngaHttpClientBuilder()
      .cookieJar(NgaCookieJar { credentials.peek() })
      .build()

  @Provides
  @Singleton
  fun provideTransportFactory(client: OkHttpClient): TransportFactory = OkHttpTransportFactory(client)

  @Provides
  @Singleton
  fun provideComboCache(): ComboCache = InMemoryComboCache()

  @Provides
  @Singleton
  fun provideSystemUserAgent(@ApplicationContext context: Context): SystemUserAgent =
    SystemUserAgent(
      onMainThread = { Looper.myLooper() == Looper.getMainLooper() },
      readSystemUserAgent = { WebSettings.getDefaultUserAgent(context) },
      postToMainThread = { task -> Handler(Looper.getMainLooper()).post { task() } },
    )

  @Provides
  @Singleton
  fun provideUserAgents(systemUserAgent: SystemUserAgent): UserAgents =
    UserAgents { systemUserAgent.get() }

  @Provides
  @Singleton
  fun provideNgaClient(
    transports: TransportFactory,
    credentialSource: CredentialSource,
    credentialCache: CurrentCredentialCache,
    settings: NetworkSettingsSource,
    userAgents: UserAgents,
    comboCache: ComboCache,
    topicCache: TopicCacheReader,
    diagnostics: DiagnosticLogStore,
    @IoScope scope: CoroutineScope,
  ): NgaClient {
    val recording = object : CredentialSource {
      override suspend fun current(): Credential? =
        credentialSource.current().also { credentialCache.remember(it) }

      override suspend fun all(): List<Credential> = credentialSource.all()
    }
    val onDiagnostic: (FetchDiagnostic) -> Unit = { diagnostic ->
      scope.launch { diagnostics.record(diagnostic.toRecord()) }
    }
    return NgaClient(
      transports = transports,
      credentials = recording,
      settings = settings,
      userAgents = userAgents,
      comboCache = comboCache,
      readChain = NgaClient.defaultReadChain(
        listCredentials = { recording.all() },
        topicCacheReader = topicCache,
      ),
      onDiagnostic = onDiagnostic,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {

  @Binds
  abstract fun bindNetworkSettingsSource(source: SettingsNetworkSource): NetworkSettingsSource

  @Binds
  abstract fun bindTopicCacheReader(reader: TopicCachePayloadReader): TopicCacheReader
}
