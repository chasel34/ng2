package com.chasel.ng2n.data.settings

import com.chasel.ng2n.core.net.DEFAULT_NGA_HOST
import com.chasel.ng2n.core.net.NGA_HOSTS
import com.chasel.ng2n.core.net.WebFallbackMode
import com.chasel.ng2n.data.account.FakePreferencesDataStore
import com.chasel.ng2n.data.net.SettingsNetworkSource
import com.chasel.ng2n.ui.settings.resolveDark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.chasel.ng2n.core.local.ImageQuality as PolicyImageQuality

class SettingsEffectiveTest {

  private fun store() = SettingsStore(FakePreferencesDataStore())

  @Test
  fun `域名改完 下一个请求现读到的就是新域名`() = runTest {
    val store = store()
    val source = SettingsNetworkSource(store)
    assertEquals(DEFAULT_NGA_HOST, source.host())

    val other = NGA_HOSTS.first { it != DEFAULT_NGA_HOST }
    store.updateSettings { it.copy(host = other) }

    assertEquals(other, source.host())
  }

  @Test
  fun `Web 反解档位与 UA 开关同样是现读`() = runTest {
    val store = store()
    val source = SettingsNetworkSource(store)
    assertEquals(DEFAULT_WEB_FALLBACK_MODE, source.webFallbackMode())
    assertTrue(source.readPhpUserAgent() != null)

    store.setWebFallbackMode(WebFallbackMode.ONLY)
    store.setReadPhpWindowsPhoneUa(false)

    assertEquals(WebFallbackMode.ONLY, source.webFallbackMode())
    assertEquals(null, source.readPhpUserAgent())
  }

  @Test
  fun `两项图片设置改完 图片管线读到的档位跟着变`() = runTest {
    val store = store()
    fun current() = store.settings
    assertTrue(current().first().wifiOnlyImages)
    assertEquals(ImageQuality.SMART, current().first().imageQuality)

    store.updateSettings { it.copy(wifiOnlyImages = false, imageQuality = ImageQuality.THUMBNAIL) }

    assertFalse(current().first().wifiOnlyImages)
    assertEquals(ImageQuality.THUMBNAIL, current().first().imageQuality)
    assertEquals(PolicyImageQuality.THUMBNAIL, policyQualityOf(current().first().imageQuality))
  }

  private fun policyQualityOf(quality: ImageQuality): PolicyImageQuality = when (quality) {
    ImageQuality.ORIGINAL -> PolicyImageQuality.ORIGINAL
    ImageQuality.SMART -> PolicyImageQuality.SMART
    ImageQuality.THUMBNAIL -> PolicyImageQuality.THUMBNAIL
  }

  @Test
  fun `恢复默认把设置表 夜间模式 网络两项一起归位`() = runTest {
    val store = store()
    store.updateSettings { it.copy(leftHanded = true, showSignature = false) }
    store.setThemeMode(ThemeMode.DARK)
    store.setWebFallbackMode(WebFallbackMode.ONLY)
    store.setReadPhpWindowsPhoneUa(false)

    store.resetAll()

    assertEquals(DEFAULT_SETTINGS, store.currentSettings())
    assertEquals(ThemeMode.SYSTEM, store.themeMode.first())
    assertEquals(NetSettings(), store.currentNetSettings())
  }

  @Test
  fun `恢复默认不碰屏蔽规则`() = runTest {
    val store = store()
    store.updateFilterRules {
      listOf(
        FilterRule(
          id = "local:keyword:广告",
          kind = FilterRuleKind.KEYWORD.wire,
          origin = FilterRuleOrigin.LOCAL.wire,
          value = "广告",
        ),
      )
    }

    store.resetAll()

    assertEquals(1, store.localFilterRules.first().size)
  }

  @Test
  fun `跟随系统时深浅由系统说了算 手动档不受系统影响`() {
    assertTrue(resolveDark(ThemeMode.SYSTEM, systemDark = true))
    assertFalse(resolveDark(ThemeMode.SYSTEM, systemDark = false))
    assertTrue(resolveDark(ThemeMode.DARK, systemDark = false))
    assertFalse(resolveDark(ThemeMode.LIGHT, systemDark = true))
  }
}
