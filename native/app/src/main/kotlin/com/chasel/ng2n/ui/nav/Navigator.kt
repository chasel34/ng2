package com.chasel.ng2n.ui.nav

import androidx.navigation3.runtime.NavKey

/**
 * 屏幕之间唯一的跳转口子。
 *
 * 刻意只有两个方法:屏幕不该拿到 back stack 本体(拿到就会有人 `removeAll`、
 * 有人插队),`Ng2nApp` 在装配处把它实现成对 `NavBackStack` 的两次调用。
 *
 * **票 13 / 15 / 16 / 17 共用同一份**(并行期各票都会建这个文件,内容保持一致,
 * 主控合并时去重)。
 */
interface Navigator {
  fun push(key: NavKey)
  fun pop()
}
