package com.chasel.ng2n.di

import android.app.ActivityManager
import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.DefaultAttachmentUrls
import com.chasel.ng2n.core.local.ImageSettingsSource
import com.chasel.ng2n.core.local.ImageSizeCache
import com.chasel.ng2n.core.local.ImageSizeStore
import com.chasel.ng2n.core.local.MeteredNetworkSource
import com.chasel.ng2n.data.FileImageSizeStore
import com.chasel.ng2n.data.InMemoryImageSettingsSource
import com.chasel.ng2n.data.MeteredNetworkMonitor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** 与进程同寿的协程作用域(记忆表的防抖落盘、Application 里的预热都挂它)。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * 图片管线装配(票 12)。
 *
 * Coil 3 挂的是 [NetworkModule] 提供的**同一个** [OkHttpClient] —— 帖内图会自动带上
 * Cookie/UA,附件域名要登录态的场景才不豆腐(票面第一句)。
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

  @Provides
  @Singleton
  @AppScope
  fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** TODO(票 10):换成票 10 与 44 条金样本对拍过的实现。 */
  @Provides
  @Singleton
  fun provideAttachmentUrls(): AttachmentUrls = DefaultAttachmentUrls

  @Provides
  @Singleton
  fun provideImageSizeCache(
    @AppScope scope: CoroutineScope,
    store: ImageSizeStore,
  ): ImageSizeCache = ImageSizeCache(scope = scope, store = store)

  /**
   * 缓存策略照抄 RN 版(`research/inventory.md` §6):
   *
   * - **内存 + 磁盘两级都开**。RN 侧正文图/头像走 `memory-disk` 而不是 `disk`,理由记在
   *   `content-image.tsx:135`:只用磁盘的话列表回收后同一张图重新上屏要再读一次盘、
   *   再解一次码,来回滚就是反复付解码钱。**查看器**那一档单独把内存缓存关掉,
   *   见 `ui/image/ImageViewerScreen.kt` 里的长注释(整屏原图会把共用池挤空)。
   * - 内存池取「应用可用堆」的 25%:Coil 的默认口径,和 anzong 的 `memoryClass/3` 同量级。
   * - 磁盘缓存放 cacheDir,256MB —— app 层不管 TTL,与 RN 侧一致(系统清缓存即可)。
   */
  @Provides
  @Singleton
  fun provideImageLoader(
    @ApplicationContext context: Context,
    client: OkHttpClient,
  ): ImageLoader {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val heapBytes = (activityManager?.memoryClass ?: 128).toLong() * 1024L * 1024L
    return ImageLoader.Builder(context)
      .components {
        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
        // 票 11:随包表情 265 张里有 27 张 GIF(默认套整套都是)。Coil 核心只解静态图,
        // 不挂这个 decoder 那 27 个表情在正文里就是一帧不动的静态图。
        // API 28 起 ImageDecoder 原生支持动图,minSdk 31 一律走这条。
        add(AnimatedImageDecoder.Factory())
      }
      .memoryCache {
        MemoryCache.Builder()
          .maxSizeBytes((heapBytes * MEMORY_CACHE_FRACTION).toLong())
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(context.cacheDir.resolve(DISK_CACHE_DIR).toOkioPath())
          .maxSizeBytes(DISK_CACHE_BYTES)
          .build()
      }
      .crossfade(CROSSFADE_MS)
      .build()
  }

  private const val MEMORY_CACHE_FRACTION = 0.25
  private const val DISK_CACHE_DIR = "coil3_image_cache"
  private const val DISK_CACHE_BYTES = 256L * 1024L * 1024L

  /** 与 RN 侧 `transition={120}` 同值。 */
  const val CROSSFADE_MS = 120
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageBindingsModule {

  @Binds
  abstract fun bindImageSizeStore(impl: FileImageSizeStore): ImageSizeStore

  /** TODO(票 14/17):换成 DataStore 支持的实现。 */
  @Binds
  abstract fun bindImageSettingsSource(impl: InMemoryImageSettingsSource): ImageSettingsSource

  @Binds
  abstract fun bindMeteredNetworkSource(impl: MeteredNetworkMonitor): MeteredNetworkSource
}
