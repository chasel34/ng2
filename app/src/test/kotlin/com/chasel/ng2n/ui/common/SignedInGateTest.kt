package com.chasel.ng2n.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 票 30 / 31 的回归:游客态碰到云端写操作时的落点。
 *
 * 这两张票是同一个形状的两种坏法 —— 屏里那句
 * `val uid = currentAccountOf(state)?.uid ?: return` 让游客点下去要么彻底静默
 * (票 30 的「新建收藏夹」),要么被 `runCatching{}.fold(onSuccess = …)` 当成做成了、
 * 报一句「已清空全部通知」(票 31)。闸门有返回值之后,「没做」这件事就藏不住了。
 */
class SignedInGateTest {

  @Test
  fun `游客态要的是登录引导而不是执行`() {
    val gate = signedInGate(null, "登录后才能管理云端收藏夹")
    assertIs<SignedInGate.NeedLogin>(gate)
    assertEquals("登录后才能管理云端收藏夹", gate.message)
  }

  @Test
  fun `登录态把 uid 原样交给写操作`() {
    val gate = signedInGate("60998369", "登录后才能管理云端收藏夹")
    assertIs<SignedInGate.Proceed>(gate)
    assertEquals("60998369", gate.uid)
  }

  @Test
  fun `话术由调用方给 不同入口各说各的事`() {
    // 收藏夹管理与通知屏共用这个闸门,但递出去的那句话不一样
    val folders = signedInGate(null, "登录后才能管理云端收藏夹")
    val notifications = signedInGate(null, "登录后才能清空通知")
    assertEquals("登录后才能管理云端收藏夹", assertIs<SignedInGate.NeedLogin>(folders).message)
    assertEquals("登录后才能清空通知", assertIs<SignedInGate.NeedLogin>(notifications).message)
  }

  /**
   * 票 31 的核心:同一段「成功就报一句」的善后代码,过闸门之后游客拿不到那句话。
   * 这里把通知屏那段 `fold` 的形状照抄成一个最小的壳,钉住「不报假成功」。
   */
  @Test
  fun `过了闸门的游客不会看到成功话术`() {
    val said = mutableListOf<String>()
    fun clearAll(uid: String?) {
      when (val gate = signedInGate(uid, "登录后才能清空通知")) {
        is SignedInGate.NeedLogin -> said += gate.message
        is SignedInGate.Proceed -> said += "已清空全部通知"
      }
    }

    clearAll(null)
    clearAll("60998369")

    assertEquals(listOf("登录后才能清空通知", "已清空全部通知"), said)
  }

  @Test
  fun `空串 uid 不算游客 交给上层去判`() {
    // `currentAccountOf` 要么给一个真账号要么给 null,这里只钉「判据是 null 而不是空」——
    // 免得日后有人把它改成 isNullOrEmpty 顺手改掉语义
    assertIs<SignedInGate.Proceed>(signedInGate("", "登录后才能清空通知"))
  }
}
