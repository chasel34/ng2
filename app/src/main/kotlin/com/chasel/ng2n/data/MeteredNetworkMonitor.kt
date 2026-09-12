package com.chasel.ng2n.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.chasel.ng2n.core.local.MeteredNetworkSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class MeteredNetworkMonitor @Inject constructor(
  @ApplicationContext context: Context,
) : MeteredNetworkSource {

  private val _metered = MutableStateFlow(false)
  override val metered: StateFlow<Boolean> = _metered.asStateFlow()

  private val connectivity = context.getSystemService(ConnectivityManager::class.java)

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
      _metered.value = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun onLost(network: Network) {
      _metered.value = false
    }
  }

  init {
    runCatching {
      val current = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
      if (current != null) {
        _metered.value = !current.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      }
      connectivity?.registerDefaultNetworkCallback(callback)
    }
  }
}
