package com.chasel.ng2n.ui.nav

import androidx.navigation3.runtime.NavKey

interface Navigator {

  fun push(key: NavKey)

  fun pop()
}

object NoopNavigator : Navigator {
  override fun push(key: NavKey) = Unit
  override fun pop() = Unit
}
