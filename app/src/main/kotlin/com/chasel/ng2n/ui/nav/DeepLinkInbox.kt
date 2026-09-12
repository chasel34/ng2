package com.chasel.ng2n.ui.nav

import android.content.Intent
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DeepLinkInbox {

  private val state = MutableStateFlow<NavKey?>(null)

  val pending: StateFlow<NavKey?> = state.asStateFlow()

  fun offer(intent: Intent?): Boolean {
    val raw = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return false
    val key = navKeyForLink(raw) ?: return false
    state.value = key
    return true
  }

  fun consume() {
    state.value = null
  }
}
