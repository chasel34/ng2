package com.chasel.ng2n.ui.common

import com.chasel.ng2n.ui.nav.LoginKey
import com.chasel.ng2n.ui.nav.Navigator

/**
 * 需要登录的入口在游客态下的统一处理 —— 直译 RN 侧 `ui/login-prompt.ts`。
 *
 * 云端功能(版块收藏、收藏夹、通知…)的接口对游客一律回「你必须先登录论坛」,
 * 把这句服务端错误直接摔给用户既难懂也没出路,所以入口先自己挡住,
 * 并把登录页递到手边。
 */
fun showLoginPrompt(nav: Navigator, message: String) {
  Snackbars.show(message, SnackbarAction("去登录") { nav.push(LoginKey) })
}
