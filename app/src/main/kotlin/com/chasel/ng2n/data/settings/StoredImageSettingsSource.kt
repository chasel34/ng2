package com.chasel.ng2n.data.settings

import com.chasel.ng2n.core.local.ImageSettings
import com.chasel.ng2n.core.local.ImageSettingsSource
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import com.chasel.ng2n.core.local.ImageQuality as PolicyImageQuality

/**
 * 图片管线的设置读口,接 DataStore(票 17c 把票 12 那个内存占位换掉)。
 *
 * 「仅 Wi-Fi 下加载图片」与「图片加载策略」在设置页一改,这条 `StateFlow` 就变,
 * `PostImage` / 查看器下一帧就按新档位取址 —— **不用重启,也不用清图片缓存**
 * (Coil 的 key 是最终 URL,换档等于换 key)。
 *
 * `SharingStarted.Eagerly` 是有意的:图片管线在**列表滚动的热路径**上读它,
 * 那儿只能同步取 `.value`,不能等一个冷流第一次发射。首帧读到的是默认值
 * (与 [DEFAULT_SETTINGS] 同),存档到位后立刻覆盖 —— 这条路上不许有同步磁盘 IO(P2-04)。
 */
@Singleton
class StoredImageSettingsSource @Inject constructor(
  store: SettingsStore,
  @IoScope scope: CoroutineScope,
) : ImageSettingsSource {

  override val imageSettings: StateFlow<ImageSettings> = store.settings
    .map { settings ->
      ImageSettings(
        wifiOnlyImages = settings.wifiOnlyImages,
        imageQuality = when (settings.imageQuality) {
          ImageQuality.ORIGINAL -> PolicyImageQuality.ORIGINAL
          ImageQuality.SMART -> PolicyImageQuality.SMART
          ImageQuality.THUMBNAIL -> PolicyImageQuality.THUMBNAIL
        },
      )
    }
    .distinctUntilChanged()
    .stateIn(
      scope = scope,
      started = SharingStarted.Eagerly,
      initialValue = ImageSettings(
        wifiOnlyImages = DEFAULT_SETTINGS.wifiOnlyImages,
        imageQuality = PolicyImageQuality.SMART,
      ),
    )
}
