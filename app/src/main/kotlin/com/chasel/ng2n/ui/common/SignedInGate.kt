package com.chasel.ng2n.ui.common

/**
 * 「需要登录才能做」的写入口在游客态的落点(票 30 / 31)。
 *
 * 云端写操作(收藏夹增删改、清空通知、官方屏蔽词……)在屏里都长成
 * `val uid = currentAccountOf(state)?.uid ?: return` 这一句 —— 于是游客点下去要么
 * **一声不吭**(票 30 的「新建收藏夹」),要么因为那个 `return` 是正常返回而被
 * `runCatching{}.fold(onSuccess = …)` 当成做成了,**谎报成功**(票 31 的「已清空全部通知」)。
 *
 * 把这个判断从「一句 elvis」提成一个有返回值的闸门,是为了让它有个能在 JVM 单测里
 * 钉住的形状:门控本身是纯逻辑,而它原来只存在于 Compose 函数体里,测不到。
 * 拿到 [NeedLogin] 的调用方一律走 [showLoginPrompt](范式:`ui/filters/FiltersScreen.kt`),
 * **不许再报成功**。
 */
sealed interface SignedInGate {
  /** 已登录,带上写操作要用的 uid。 */
  data class Proceed(val uid: String) : SignedInGate

  /** 游客态。[message] 是要递给用户的那句「登录后才能…」。 */
  data class NeedLogin(val message: String) : SignedInGate
}

/**
 * [uid] 为空就是游客态。[message] 写成「登录后才能<做什么>」——
 * 与 `showLoginPrompt` 的「去登录」按钮凑成一句完整的出路。
 */
fun signedInGate(uid: String?, message: String): SignedInGate =
  if (uid == null) SignedInGate.NeedLogin(message) else SignedInGate.Proceed(uid)
