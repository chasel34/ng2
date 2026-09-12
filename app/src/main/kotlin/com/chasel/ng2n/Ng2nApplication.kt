package com.chasel.ng2n

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.chasel.ng2n.core.local.ImageSizeCache
import com.chasel.ng2n.data.StorageBootstrap
import com.chasel.ng2n.data.net.SystemUserAgent
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class Ng2nApplication : Application(), SingletonImageLoader.Factory {

  @Inject
  lateinit var storageBootstrap: StorageBootstrap

  @Inject lateinit var imageLoaderProvider: Provider<ImageLoader>

  @Inject lateinit var imageSizeCache: ImageSizeCache

  @Inject lateinit var systemUserAgent: SystemUserAgent

  private var liveImageLoader: ImageLoader? = null

  override fun onCreate() {
    installStrictMode()
    super.onCreate()
    systemUserAgent.prewarm()
    storageBootstrap.start()
    imageSizeCache.warmUpAsync()
  }

  override fun newImageLoader(context: PlatformContext): ImageLoader =
    imageLoaderProvider.get().also { liveImageLoader = it }

  @Suppress("DEPRECATION")
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    val cache = liveImageLoader?.memoryCache ?: return
    val before = cache.size
    when {
      level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.clear()
      level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> cache.trimToSize(cache.size / 2)
      level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.clear()
      else -> return
    }
    Log.i(TAG, "onTrimMemory(level=$level) coil memoryCache ${before / 1024}KB -> ${cache.size / 1024}KB")
  }

  private fun installStrictMode() {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return

    val thread = StrictMode.ThreadPolicy.Builder()
      .detectDiskReads()
      .detectDiskWrites()
      .detectNetwork()
      .detectCustomSlowCalls()
      .penaltyLog()
      .apply { if (PENALTY_DEATH) penaltyDeath() }
      .build()
    StrictMode.setThreadPolicy(thread)

    StrictMode.setVmPolicy(
      StrictMode.VmPolicy.Builder()
        .detectLeakedSqlLiteObjects()
        .detectLeakedClosableObjects()
        .penaltyLog()
        .build(),
    )
  }

  private companion object {
    const val TAG = "Ng2nApplication"

    const val PENALTY_DEATH = false
  }
}
