package com.chasel.ng2n.ui.image

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.local.ImageSettings
import com.chasel.ng2n.core.local.ImageSettingsSource
import com.chasel.ng2n.core.local.ImageSizeCache
import com.chasel.ng2n.core.local.MeteredNetworkSource
import com.chasel.ng2n.data.ImageSaver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

@Singleton
class ImagePipeline @Inject constructor(
  val sizeCache: ImageSizeCache,
  val saver: ImageSaver,
  val attachmentUrls: AttachmentUrls,
  settingsSource: ImageSettingsSource,
  meteredSource: MeteredNetworkSource,
) {
  val settings: StateFlow<ImageSettings> = settingsSource.imageSettings
  val metered: StateFlow<Boolean> = meteredSource.metered
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImagePipelineEntryPoint {
  fun imagePipeline(): ImagePipeline
}

val LocalImagePipeline = compositionLocalOf<ImagePipeline?> { null }

@Composable
fun rememberImagePipeline(): ImagePipeline {
  LocalImagePipeline.current?.let { return it }
  val context: Context = LocalContext.current
  return remember(context) {
    EntryPointAccessors
      .fromApplication(context.applicationContext, ImagePipelineEntryPoint::class.java)
      .imagePipeline()
  }
}
