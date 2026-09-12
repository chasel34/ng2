package com.chasel.ng2n.ui.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmileyResolveTest {

  @Test
  fun `生成表规模与 RN 侧一致`() {
    assertEquals(7, SMILEY_CATEGORIES.size)
    assertEquals(265, SMILEY_CATEGORIES.sumOf { it.entries.size })
    assertEquals(265, BUNDLED_SMILEY_FILES.size)
  }

  @Test
  fun `随包文件都读到了原始尺寸`() {
    val missing = BUNDLED_SMILEY_FILES.filter { file ->
      val size = BUNDLED_SMILEY_SIZES[file]
      size == null || size.width <= 0 || size.height <= 0
    }
    assertTrue(missing.isEmpty(), "这些随包表情没有尺寸:$missing")
  }

  @Test
  fun `分类加名称的写法`() {
    val smiley = resolveSmiley("ac:blink")
    assertTrue(smiley is ResolvedSmiley.Bundled)
    assertEquals("ac0.png", smiley.file)
    assertEquals("AC娘(v1)", smiley.label)
    assertEquals("file:///android_asset/smilies/ac0.png", smiley.assetUrl)
  }

  @Test
  fun `纯数字走默认套`() {
    val smiley = resolveSmiley("1")
    assertTrue(smiley is ResolvedSmiley.Bundled)
    assertEquals("smile.gif", smiley.file)
    assertEquals("0", smiley.category)
  }

  @Test
  fun `零不是数字套 官方用 parseInt 取真假`() {
    assertTrue(resolveSmiley("0") is ResolvedSmiley.Unresolved)
  }

  @Test
  fun `分类为空时退回默认套`() {
    val smiley = resolveSmiley(":1")
    assertTrue(smiley is ResolvedSmiley.Bundled)
    assertEquals("smile.gif", smiley.file)
  }

  @Test
  fun `表里查不到就还原原文`() {
    assertEquals(
      ResolvedSmiley.Unresolved("[s:ac:不存在]"),
      resolveSmiley("ac:不存在"),
    )
    assertEquals(ResolvedSmiley.Unresolved("[s:zz:x]"), resolveSmiley("zz:x"))
  }

  @Test
  fun `表里有但包里没有的走 CDN`() {
    val smiley = resolveSmiley("ac:blink", bundledFiles = emptySet())
    assertTrue(smiley is ResolvedSmiley.Remote)
    assertEquals("https://img4.nga.cn/ngabbs/post/smile/ac0.png", smiley.remoteUrl)
  }
}
