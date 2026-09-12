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
