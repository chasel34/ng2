package com.chasel.ng2n.ui.common

import com.chasel.ng2n.ui.Login
import com.chasel.ng2n.ui.nav.Navigator

fun showLoginPrompt(nav: Navigator, message: String) {
  Snackbars.show(message, SnackbarAction("去登录") { nav.push(Login) })
}
