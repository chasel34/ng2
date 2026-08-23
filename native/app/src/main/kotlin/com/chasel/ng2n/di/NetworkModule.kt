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

/**
 * 传输层与反封锁链的装配(票 06)。
 *
 * 骨架期这里只有一个裸 [OkHttpClient] 占位(票 12 的图片管线需要「和协议层同一个 client」
 * 才能让附件域名带上登录态)。**provider 的签名与作用域没有变**,图片侧一行不动。
 *
 * ## 这个 client 长什么样
 *
 * - 统一超时预算(修 P2-06):connect / read / write / 整体 call 各一档,常量在
 *   `core/net/Timeouts.kt`;
 * - **自管 CookieJar**(修 P1-03):只装 `ngaPassportUid` / `ngaPassportCid`,
 *   不碰 WebView 的 `CookieManager`,也不保存服务端下发的任何 cookie。
 *   协议层每一发请求会派生一个带「这一发钉死的身份」的 client;
 *   这里给的默认 jar 服务于图片管线,读 [CurrentCredentialCache] 的现值;
 * - `retryOnConnectionFailure = false`:反封锁链自己就是重试机制,okhttp 再悄悄重试
 *   会让「试了几个组合」对不上账。
 *
 * UA / `X-User-Agent` / Referer **不在这里注入**:它们逐请求不同(`read.php` 可切
 * Windows Phone UA、Referer 跟着当前域名走),由 `runAttempt` 拼进 headers。
 */
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

  /**
   * 成功组合缓存。建在链外面(而不是让 client 自己建)是为了让 UI 能清它:
   * 详情页的「重试原生」要从头试探,不能又从上次那个已经不灵的组合开局。
   */
  @Provides
  @Singleton
  fun provideComboCache(): ComboCache = InMemoryComboCache()

  /**
   * 系统 WebView UA 的取值器(票 35)。
   *
   * `WebSettings.getDefaultUserAgent()` 只在**主线程**上调用:第一次调用要把 WebView
   * provider 拉起来,而 provider 的启动只能在 UI 线程跑 —— 在后台线程调它,它会
   * `CountDownLatch.await()` 等主线程。旧实现把这一句包在 `lazy` 里(求值全程持锁),
   * 于是「等主线程」变成「攥着锁等主线程」,主线程随后来求同一把锁 → 死锁 → ANR。
   * 三条纪律(主线程求值 / 非主线程不阻塞 / 全程不持锁)写在 [SystemUserAgent] 的 KDoc 里。
   *
   * 预热在 `Ng2nApplication.onCreate`。
   */
  @Provides
  @Singleton
  fun provideSystemUserAgent(@ApplicationContext context: Context): SystemUserAgent =
    SystemUserAgent(
      onMainThread = { Looper.myLooper() == Looper.getMainLooper() },
      readSystemUserAgent = { WebSettings.getDefaultUserAgent(context) },
      postToMainThread = { task -> Handler(Looper.getMainLooper()).post { task() } },
    )

  /**
   * 一次请求能用的 UA 表(Android v4 的现行做法,API 文档 §0.3):`webview` 档取设备侧
   * 现值,其余档位是 `core/net/Constants.kt` 里的常量。**取值永不阻塞调用线程**。
   */
  @Provides
  @Singleton
  fun provideUserAgents(systemUserAgent: SystemUserAgent): UserAgents =
    UserAgents { systemUserAgent.get() }

  /**
   * 反封锁链。链的顺序即 ADR-0002 的顺序,写请求走另一条只有 direct 的链(修 P1-01)。
   *
   * 网页反解器(票 08)与帖子缓存读口(票 14)在这里注入;前者现在还是占位实现,
   * 那两档在链上直接让位,不白打一次网络请求。
   */
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
    // 每请求现读凭证,顺手把它记给图片管线(它拿不到协程上下文去读 DataStore)
    val recording = object : CredentialSource {
      override suspend fun current(): Credential? =
        credentialSource.current().also { credentialCache.remember(it) }

      override suspend fun all(): List<Credential> = credentialSource.all()
    }
    val onDiagnostic: (FetchDiagnostic) -> Unit = { diagnostic ->
      // 落盘是 suspend 的,而链是同步回调 —— 丢到后台 scope,别让排障旁路拖住请求
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

/** core 层的两个接口绑到 data 层实现。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {

  @Binds
  abstract fun bindNetworkSettingsSource(source: SettingsNetworkSource): NetworkSettingsSource

  @Binds
  abstract fun bindTopicCacheReader(reader: TopicCachePayloadReader): TopicCacheReader
}
