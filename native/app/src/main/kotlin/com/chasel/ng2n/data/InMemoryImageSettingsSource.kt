package com.chasel.ng2n.data

import com.chasel.ng2n.core.local.ImageSettings
import com.chasel.ng2n.core.local.ImageSettingsSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 图片设置的**临时**实现:内存里的一份默认值,可改但不落盘。
 *
 * 设置的持久化归票 14(DataStore)、设置屏归票 17。票 12 只要一个能读的口子,
 * 于是先给这个。**TODO(票 14/17)**:换成 DataStore 支持的实现,
 * 把 [ImageSettingsSource] 的绑定改到那边,本类删除。
 */
@Singleton
class InMemoryImageSettingsSource @Inject constructor() : ImageSettingsSource {

  private val _imageSettings = MutableStateFlow(ImageSettings())
  override val imageSettings: StateFlow<ImageSettings> = _imageSettings.asStateFlow()

  /** 票 12 的模拟器手验用(demo 屏上切档);票 17 接上真设置后由那边写。 */
  fun update(transform: (ImageSettings) -> ImageSettings) {
    _imageSettings.value = transform(_imageSettings.value)
  }
}
