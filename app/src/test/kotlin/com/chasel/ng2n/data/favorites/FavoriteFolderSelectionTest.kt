package com.chasel.ng2n.data.favorites

import com.chasel.ng2n.core.api.FavoriteFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteFolderSelectionTest {

  private fun folder(id: Long, name: String, count: Long = 0, isDefault: Boolean = false) =
    FavoriteFolder(id = id, name = name, count = count, isDefault = isDefault)

  private val folders = listOf(
    folder(3, "默认收藏夹", count = 126, isDefault = true),
    folder(7, "以后再看", count = 4),
    folder(9, "攒图", count = 0),
  )

  @Test
  fun `夹列表摊成行，本机索引里记着的那几个初始就是勾上的`() {
    val rows = favoriteFolderRows(folders, listOf(7))
    assertEquals(listOf(3, 7, 9), rows.map { it.id })
    assertEquals(listOf(false, true, false), rows.map { it.checked })
  }

  @Test
  fun `副行是「N 个主题」，默认夹再缀一句`() {
    val rows = favoriteFolderRows(folders, emptyList())
    assertEquals("126 个主题 · 默认夹", rows[0].subtitle)
    assertEquals("4 个主题", rows[1].subtitle)
    assertEquals("0 个主题", rows[2].subtitle)
  }

  @Test
  fun `本机索引里记着一个已经不存在的夹，不会凭空多出一行`() {
    val rows = favoriteFolderRows(folders, listOf(7, 404))
    assertEquals(3, rows.size)
    assertEquals(listOf(false, true, false), rows.map { it.checked })
  }

  @Test
  fun `点一行来回切勾选`() {
    val once = toggleFolderSelection(listOf(3), 7)
    assertEquals(listOf(3, 7), once)
    assertEquals(listOf(3), toggleFolderSelection(once, 7))
  }

  @Test
  fun `重复点同一行不会把它塞进去两次`() {
    val on = toggleFolderSelection(emptyList(), 9)
    val off = toggleFolderSelection(on, 9)
    assertEquals(listOf(9), toggleFolderSelection(off, 9))
  }

  @Test
  fun `新建夹之后顺手勾上`() {
    assertEquals(listOf(3, 12), selectCreatedFolder(listOf(3), 12L))
  }

  @Test
  fun `服务端没回新夹 id 就原样返回，不瞎猜`() {
    assertEquals(listOf(3), selectCreatedFolder(listOf(3), null))
  }

  @Test
  fun `已经勾着的夹不会因为再建一次而重复`() {
    assertEquals(listOf(3), selectCreatedFolder(listOf(3), 3L))
  }

  @Test
  fun `到了夹上限就不再露「新建收藏夹…」`() {
    assertTrue(canCreateFavoriteFolder(FAVORITE_FOLDER_LIMIT - 1))
    assertFalse(canCreateFavoriteFolder(FAVORITE_FOLDER_LIMIT))
    assertFalse(canCreateFavoriteFolder(FAVORITE_FOLDER_LIMIT + 1))
  }

  @Test
  fun `什么都没动就是空计划，一个请求都不发`() {
    val plan = planFavoriteApply(folders, initial = listOf(3, 7), selected = listOf(7, 3))
    assertTrue(plan.isEmpty)
    assertEquals(emptyList(), plan.added)
    assertEquals(emptyList(), plan.removed)
  }

  @Test
  fun `只对改动过的夹发请求，没动过的一个都不发`() {
    val plan = planFavoriteApply(folders, initial = listOf(3, 7), selected = listOf(7, 9))
    assertFalse(plan.isEmpty)
    assertEquals(listOf(9), plan.added)
    assertEquals(listOf(3), plan.removed)
  }

  @Test
  fun `交给仓库的是 Long，索引这边一律 Int`() {
    val plan = planFavoriteApply(folders, initial = listOf(3), selected = listOf(7, 9))
    assertEquals(listOf(7L, 9L), plan.addedIds)
    assertEquals(listOf(3L), plan.removedIds)
  }

  @Test
  fun `全取消就是三个 removed`() {
    val plan = planFavoriteApply(folders, initial = listOf(9, 3, 7), selected = emptyList())
    assertEquals(emptyList(), plan.added)
    assertEquals(listOf(3, 7, 9), plan.removed)
  }

  @Test
  fun `完成提示语照设计稿那句「已收藏到「默认收藏夹」」`() {
    val plan = planFavoriteApply(folders, initial = emptyList(), selected = listOf(3))
    assertEquals("已收藏到「默认收藏夹」", plan.doneMessage)
  }

  @Test
  fun `一次既加又减就两句都说`() {
    val plan = planFavoriteApply(folders, initial = listOf(3), selected = listOf(7))
    assertEquals("已收藏到「以后再看」；已从「默认收藏夹」移出", plan.doneMessage)
  }

  @Test
  fun `勾了两个夹，名字顿号连起来`() {
    val plan = planFavoriteApply(folders, initial = emptyList(), selected = listOf(7, 9))
    assertEquals("已收藏到「以后再看、攒图」", plan.doneMessage)
  }

  @Test
  fun `夹列表里找不到的夹退到「收藏夹 N」，提示语不会变成一对空引号`() {
    assertEquals("收藏夹 404", favoriteFolderName(folders, 404))
    val plan = planFavoriteApply(folders, initial = listOf(404), selected = emptyList())
    assertEquals("已从「收藏夹 404」移出", plan.doneMessage)
  }

  @Test
  fun `什么都没动时提示语是空串（反正不会被拿去 show）`() {
    assertEquals("", favoriteResultText(emptyList(), emptyList()))
  }

  @Test
  fun `取消收藏的确认文案说清只影响这一个夹`() {
    assertEquals(
      "把「测试帖」从「以后再看」里移出？其他收藏夹里的这一帖不受影响。",
      unfavoriteConfirmMessage("测试帖", "以后再看"),
    )
  }
}
