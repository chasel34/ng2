package com.chasel.ng2n.ui.nav

import androidx.navigation3.runtime.NavKey

/**
 * 屏幕看导航的唯一口子。
 *
 * Nav3 的 back stack 是一个可变的 `NavKey` 列表,谁拿到谁就能改;
 * 而屏幕只需要「往前走一个」「退回去」两件事。抠成接口的理由有两条:
 *
 * 1. 各屏(票 13 / 16 / 17)不必各自持有 back stack,`Ng2nApp` 里只装配一次;
 * 2. 屏幕的预览/手验可以塞一个空实现,不必起整个 NavDisplay。
 */
interface Navigator {

  fun push(key: NavKey)

  fun pop()
}

/** 什么都不做的导航器(预览与手验用)。 */
object NoopNavigator : Navigator {
  override fun push(key: NavKey) = Unit
  override fun pop() = Unit
}
