package com.chasel.ng2n.core.ai

import org.junit.Assert.*
import org.junit.Test

class QuickActionsTest {
  @Test fun entryActionsAndTrailingSlashFiltering() {
    assertEquals(listOf("事实核查", "批判性思考", "梳理分歧", "补充背景"), BuiltinQuickActions.forEntry(false).map { it.label })
    assertEquals(listOf("事实核查", "批判性思考", "查找前情"), BuiltinQuickActions.forEntry(true).map { it.label })
    assertEquals("事实", BuiltinQuickActions.query("请分析 /事实"))
    assertNull(BuiltinQuickActions.query("https://example.com/a"))
    assertNull(BuiltinQuickActions.query("/事实 "))
    assertTrue(BuiltinQuickActions.filter(BuiltinQuickActions.all, "无匹配").isEmpty())
    assertEquals(10, BuiltinQuickActions.all.map { it.skillId }.distinct().size)
  }
}
