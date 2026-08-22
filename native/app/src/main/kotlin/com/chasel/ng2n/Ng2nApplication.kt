package com.chasel.ng2n

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.chasel.ng2n.core.local.ImageSizeCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class Ng2nApplication : Application(), SingletonImageLoader.Factory {

  @Inject lateinit var imageLoaderProvider: Provider<ImageLoader>

  @Inject lateinit var imageSizeCache: ImageSizeCache

  /**
   * 已经造出来的那个 loader。**不用 `SingletonImageLoader.get()` 兜底**:那个调用会
   * 顺手把 loader 造出来,于是「内存紧张时清缓存」反而变成「内存紧张时建缓存」。
   */
  private var liveImageLoader: ImageLoader? = null

  override fun onCreate() {
    super.onCreate()
    // 尺寸记忆表回灌落盘条目。**异步**:走查 P2-04 记的「冷启同步 IO」是不随迁的已知缺陷,
    // 这条读盘绝不能挡在首帧前面。没回灌完之前只是第一批图按 4:3 占位起步,和冷启一样。
    imageSizeCache.warmUpAsync()
  }

  override fun newImageLoader(context: PlatformContext): ImageLoader =
    imageLoaderProvider.get().also { liveImageLoader = it }

  /**
   * 内存紧张时把 Coil 的**内存**缓存交出去(票 12 的「白捡改进」,RN 版没做)。
   *
   * 证据在走查里:RN 版看完 20 帖 PSS 174→289MB 不回落,回收压力全压在别处;图片位图是
   * 这里面最大也最容易再拿回来的一块 —— 磁盘缓存还在,重新解码的代价远小于被系统整进程杀掉。
   *
   * `TRIM_MEMORY_*` 的数值**不按严重程度单调**(RUNNING_MODERATE 5 < RUNNING_LOW 10 <
   * RUNNING_CRITICAL 15 < UI_HIDDEN 20 < BACKGROUND 40 < MODERATE 60 < COMPLETE 80),
   * 前台档与后台档是两条线,所以先判后台再判前台,顺序不能换:
   *
   * - `>= BACKGROUND`:已经在后台且系统开始按 LRU 排队杀 → **整个清掉**;
   * - `>= UI_HIDDEN`:刚退到后台,前台像素一张都用不上 → 砍一半,留点热数据给切回来;
   * - `>= RUNNING_LOW`(含 `RUNNING_CRITICAL`):还在前台但系统已经在挨饿 → **整个清掉**;
   * - `RUNNING_MODERATE`:不动,免得把刚滚过去的那几张图也扔了。
   *
   * (Coil 自己也挂了一份 ComponentCallbacks2,策略同构;这里显式再做一遍是为了
   * 「这条策略是我们的决定、可改可测」,而不是继承第三方默认值。)
   */
  @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_* 在 API 34 起标了 deprecated,但仍会派发
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
    // 只有尺寸,没有地址也没有用户数据(P1-04 诊断日志脱敏的口径)。
    Log.i(TAG, "onTrimMemory(level=$level) coil memoryCache ${before / 1024}KB -> ${cache.size / 1024}KB")
  }

  private companion object {
    const val TAG = "Ng2nApplication"
  }
}
