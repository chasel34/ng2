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

/**
 * 图片管线对 UI 的单一门面:尺寸记忆表、两个设置、计费网络状态、落盘动作、地址工具。
 *
 * 有它是为了让 [PostImage] 这种**叶子组件**不必人手往下传五个依赖 —— 正文里几十张图,
 * 渲染器(票 11)只想写 `PostImage(url = …)`。
 */
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

/**
 * 屏级可以用 `CompositionLocalProvider(LocalImagePipeline provides …)` 一次性注入;
 * 没注入时按 Hilt 的 EntryPoint 现取(`@Singleton`,取的是同一个实例)。
 *
 * 之所以留这条兜底而不是强制屏级提供:图片组件会被票 11 的渲染器塞进任意层级,
 * 少一条「忘了 provide 就崩」的路。
 */
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
