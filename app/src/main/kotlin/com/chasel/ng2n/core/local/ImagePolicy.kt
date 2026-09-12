package com.chasel.ng2n.core.local

enum class ImageQuality {
  ORIGINAL,
  SMART,
  THUMBNAIL,
}

data class ImageSettings(
  val wifiOnlyImages: Boolean = true,
  val imageQuality: ImageQuality = ImageQuality.SMART,
)

sealed interface ImagePlan {
  data object Locked : ImagePlan

  data class Show(val url: String, val placeholderUrl: String? = null) : ImagePlan
}

object ImagePolicy {

  fun imagesUnlocked(wifiOnly: Boolean, metered: Boolean): Boolean = !wifiOnly || !metered

  fun preferThumbnail(quality: ImageQuality, metered: Boolean): Boolean = when (quality) {
    ImageQuality.THUMBNAIL -> true
    ImageQuality.ORIGINAL -> false
    ImageQuality.SMART -> metered
  }

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

interface ImageSettingsSource {
  val imageSettings: kotlinx.coroutines.flow.StateFlow<ImageSettings>
}

interface MeteredNetworkSource {
  val metered: kotlinx.coroutines.flow.StateFlow<Boolean>
}
