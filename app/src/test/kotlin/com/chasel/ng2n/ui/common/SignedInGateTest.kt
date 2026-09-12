package com.chasel.ng2n.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    val folders = signedInGate(null, "登录后才能管理云端收藏夹")
    val notifications = signedInGate(null, "登录后才能清空通知")
    assertEquals("登录后才能管理云端收藏夹", assertIs<SignedInGate.NeedLogin>(folders).message)
    assertEquals("登录后才能清空通知", assertIs<SignedInGate.NeedLogin>(notifications).message)
  }

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
    assertIs<SignedInGate.Proceed>(signedInGate("", "登录后才能清空通知"))
  }
}
