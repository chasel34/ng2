package com.chasel.ng2n.core.local

/**
 * 图片加载策略(票 12;RN 侧原件 `src/ui/network.ts` + `src/core/local/settings.ts`)。
 *
 * 纯 Kotlin,零 Android 依赖:两个开关 + 当前是不是计费网络 → 这张图该拉原图、拉缩略图,
 * 还是先不拉(折成「点击显示」)。判断搁在组件里就测不到了,所以单拎出来。
 */

/** 「图片加载策略」三档。`smart` = Wi-Fi 原图、流量缩略图。 */
enum class ImageQuality {
  ORIGINAL,
  SMART,
  THUMBNAIL,
}

/**
 * 图片相关设置。默认值照抄 RN 版 `DEFAULT_SETTINGS`
 * (`src/core/local/settings.ts:94,96`):`wifiOnlyImages = true`、`imageQuality = smart`。
 *
 * 真正的持久化归票 14(DataStore),这里只定义形状 + [ImageSettingsSource] 这个读口。
 */
data class ImageSettings(
  val wifiOnlyImages: Boolean = true,
  val imageQuality: ImageQuality = ImageQuality.SMART,
)

/** 一张图这一刻该怎么加载。 */
sealed interface ImagePlan {
  /** 「仅 Wi-Fi 下加载图片」在计费网络下的折叠态:不发请求,点一下才展开。 */
  data object Locked : ImagePlan

  /**
   * 加载 [url]。[placeholderUrl] 是原图到位之前先糊着看的缩略图(通常已有磁盘缓存);
   * 没有缩略图变体时缺席。
   */
  data class Show(val url: String, val placeholderUrl: String? = null) : ImagePlan
}

object ImagePolicy {

  /**
   * 图片能不能直接铺出来。false = 折成「点击显示」,点了照样能看。
   * 照抄 `useImagesUnlocked`:`!wifiOnly || !metered`。
   */
  fun imagesUnlocked(wifiOnly: Boolean, metered: Boolean): Boolean = !wifiOnly || !metered

  /**
   * 该拉缩略图还是原图。照抄 `usePreferThumbnail`:thumbnail 档恒 true、original 档恒 false、
   * smart 档跟当前网络走。
   */
  fun preferThumbnail(quality: ImageQuality, metered: Boolean): Boolean = when (quality) {
    ImageQuality.THUMBNAIL -> true
    ImageQuality.ORIGINAL -> false
    ImageQuality.SMART -> metered
  }

  /**
   * 正文图(RN `ContentImage`)的取址决定。
   *
   * @param url 原图地址
   * @param thumbnailUrl 同一张图的缩略图地址;站外图床没有这套后缀约定时传 null
   * @param revealed 用户已经点过折叠态的「点击显示」——这一张这次就不再折
   *
   * RN 对应:`source = preferThumbnail ? (thumbnailUri ?? uri) : uri`,外加折叠态早退。
   * 正文图**不给** placeholder:RN 侧 `ContentImage` 也没有,渐进占位只在查看器里用。
   */
  fun resolve(
    url: String,
    thumbnailUrl: String?,
    quality: ImageQuality,
    wifiOnly: Boolean,
    metered: Boolean,
    revealed: Boolean = false,
  ): ImagePlan {
    if (!revealed && !imagesUnlocked(wifiOnly, metered)) return ImagePlan.Locked
    val src = if (preferThumbnail(quality, metered)) thumbnailUrl ?: url else url
    return ImagePlan.Show(src)
  }

  /**
   * 查看器里某一页的取址决定(RN `image-viewer.tsx:66-75`)。
   *
   * 与正文图两处不同,都照抄:
   * 1. **不看 `wifiOnlyImages`** —— 是用户自己点进来看这张图的,没有再折一次的道理;
   * 2. 拉原图时把缩略图当**渐进占位**先糊着看。
   *
   * [forceOriginal] 是菜单「查看原图」对当前这一页的覆盖(按 index 记,RN 同)。
   */
  fun resolveViewer(
    url: String,
    thumbnailUrl: String?,
    quality: ImageQuality,
    metered: Boolean,
    forceOriginal: Boolean = false,
  ): ImagePlan.Show {
    if (preferThumbnail(quality, metered) && !forceOriginal && thumbnailUrl != null) {
      return ImagePlan.Show(thumbnailUrl)
    }
    return ImagePlan.Show(url, if (thumbnailUrl == url) null else thumbnailUrl)
  }
}

/**
 * 图片设置的读口。实现现在是内存默认值(`data/InMemoryImageSettingsSource`),
 * 票 14 的 DataStore / 票 17 的设置屏接上来之后换实现即可,调用方不动。
 */
interface ImageSettingsSource {
  val imageSettings: kotlinx.coroutines.flow.StateFlow<ImageSettings>
}

/** 当前是不是计费网络的读口。实现在 `data/MeteredNetworkMonitor`(ConnectivityManager 回调)。 */
interface MeteredNetworkSource {
  val metered: kotlinx.coroutines.flow.StateFlow<Boolean>
}
