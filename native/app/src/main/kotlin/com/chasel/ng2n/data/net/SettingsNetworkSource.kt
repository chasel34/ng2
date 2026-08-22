package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.NetworkSettingsSource
import com.chasel.ng2n.core.net.UserAgentProfile
import com.chasel.ng2n.core.net.WebFallbackMode
import com.chasel.ng2n.data.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反封锁链每请求现读的那几项设置,接 DataStore。
 *
 * **每请求现读**是 RN 版就有的语义:设置页改完,下一个请求就发到新域名 / 新档位,
 * 不必重建 client;在途的那个请求仍按发起时的取值走。
 *
 * `readPhpWindowsPhoneUa` 开着时把 `read.php` 的 UA 换成
 * [UserAgentProfile.WINDOWS_PHONE](ADR-0002:MNGA 强制用它,实测更不容易被封),
 * **默认开** —— RN 版 07 票起就一直用这一档并已过真机验收。
 */
@Singleton
class SettingsNetworkSource @Inject constructor(
  private val store: SettingsStore,
) : NetworkSettingsSource {

  override suspend fun host(): String = store.currentSettings().host

  override suspend fun webFallbackMode(): WebFallbackMode = store.currentNetSettings().webFallbackMode

  override suspend fun readPhpUserAgent(): UserAgentProfile? =
    if (store.currentNetSettings().readPhpWindowsPhoneUa) UserAgentProfile.WINDOWS_PHONE else null
}
