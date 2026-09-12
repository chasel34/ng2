package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.NetworkSettingsSource
import com.chasel.ng2n.core.net.UserAgentProfile
import com.chasel.ng2n.core.net.WebFallbackMode
import com.chasel.ng2n.data.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsNetworkSource @Inject constructor(
  private val store: SettingsStore,
) : NetworkSettingsSource {

  override suspend fun host(): String = store.currentSettings().host

  override suspend fun webFallbackMode(): WebFallbackMode = store.currentNetSettings().webFallbackMode

  override suspend fun readPhpUserAgent(): UserAgentProfile? =
    if (store.currentNetSettings().readPhpWindowsPhoneUa) UserAgentProfile.WINDOWS_PHONE else null
}
