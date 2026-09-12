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
import com.chasel.ng2n.data.settings.StoredImageSettingsSource
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

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

  @Provides
  @Singleton
  @AppScope
  fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Provides
  @Singleton
  fun provideAttachmentUrls(): AttachmentUrls = DefaultAttachmentUrls

  @Provides
  @Singleton
  fun provideImageSizeCache(
    @AppScope scope: CoroutineScope,
    store: ImageSizeStore,
  ): ImageSizeCache = ImageSizeCache(scope = scope, store = store)

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

  const val CROSSFADE_MS = 120
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageBindingsModule {

  @Binds
  abstract fun bindImageSizeStore(impl: FileImageSizeStore): ImageSizeStore

  @Binds
  abstract fun bindImageSettingsSource(impl: StoredImageSettingsSource): ImageSettingsSource

  @Binds
  abstract fun bindMeteredNetworkSource(impl: MeteredNetworkMonitor): MeteredNetworkSource
}
