package com.chasel.ng2n.data.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `src/core/local/topic-favor-index.test.ts` 的手工移植(票 14 验收项①)。
 */
class TopicFavorIndexTest {

  private fun json(raw: String) = Json.parseToJsonElement(raw)

  // ------------------------------------------------------------ applyFavoriteChange

  @Test
  fun `收藏到多个夹后两个夹都记着`() {
    var index = applyFavoriteChange(
      EMPTY_TOPIC_FAVOR_INDEX,
      FavoriteChange(tid = 45150945, folderId = 7, favored = true),
    )
    index = applyFavoriteChange(index, FavoriteChange(45150945, 3, favored = true))

    assertEquals(listOf(3, 7), foldersOfTopic(index, 45150945))
  }

  @Test
  fun `取消一个夹不动其他夹`() {
    var index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, folderId = 7, tids = listOf(1))
    index = seedFolderTopics(index, folderId = 3, tids = listOf(1))
    index = applyFavoriteChange(index, FavoriteChange(1, 7, favored = false))

    assertEquals(listOf(3), foldersOfTopic(index, 1))
  }

  @Test
  fun `重复收藏同一个夹不会记两条`() {
    var index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, FavoriteChange(1, 7, true))
    index = applyFavoriteChange(index, FavoriteChange(1, 7, true))

    assertEquals(listOf(7), foldersOfTopic(index, 1))
  }

  @Test
  fun `最后一个夹取消掉后整条记录消失 不留空数组`() {
    var index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, FavoriteChange(1, 7, true))
    index = applyFavoriteChange(index, FavoriteChange(1, 7, false))

    assertEquals(emptyMap(), index)
  }

  @Test
  fun `没记录过的主题每次拿到同一个空 List`() {
    assertSame(foldersOfTopic(EMPTY_TOPIC_FAVOR_INDEX, 1), foldersOfTopic(mapOf(2L to listOf(7)), 1))
  }

  @Test
  fun `取消一个从没记过的夹是空操作`() {
    assertEquals(
      emptyMap(),
      applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, FavoriteChange(1, 7, false)),
    )
  }

  // ------------------------------------------------------------ seedFolderTopics

  @Test
  fun `把一页列表里的 tid 都记进这个夹`() {
    val index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, 7, listOf(11, 22, 22))

    assertEquals(listOf(7), foldersOfTopic(index, 11))
    assertEquals(listOf(7), foldersOfTopic(index, 22))
  }

  @Test
  fun `只翻了一页时不敢清记录 没出现的主题可能在后面几页`() {
    var index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, FavoriteChange(99, 7, true))
    index = seedFolderTopics(index, 7, listOf(11))

    assertEquals(listOf(7), foldersOfTopic(index, 99))
  }

  @Test
  fun `整个夹就这一页时 清掉本机记错的归属`() {
    var index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, FavoriteChange(99, 7, true))
    index = applyFavoriteChange(index, FavoriteChange(99, 3, true))
    index = seedFolderTopics(index, 7, listOf(11), complete = true)

    // 7 号夹的全集里没有 99,所以只摘掉 7;99 在 3 号夹里的归属不受影响
    assertEquals(listOf(3), foldersOfTopic(index, 99))
    assertEquals(listOf(7), foldersOfTopic(index, 11))
  }

  @Test
  fun `空夹 complete 且一条都没有 把这个夹的记录清干净`() {
    var index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, FavoriteChange(99, 7, true))
    index = seedFolderTopics(index, 7, emptyList(), complete = true)

    assertEquals(emptyMap(), index)
  }

  // ------------------------------------------------------------ pruneFolders

  @Test
  fun `删掉的夹留下的归属一并清掉`() {
    var index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, 7, listOf(1, 2))
    index = seedFolderTopics(index, 3, listOf(1))
    index = pruneFolders(index, listOf(3))

    assertEquals(listOf(3), foldersOfTopic(index, 1))
    // 2 只在被删的 7 号夹里,整条记录就没了
    assertEquals(mapOf(1L to listOf(3)), index)
  }

  @Test
  fun `没有夹被删时原样返回`() {
    val index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, 7, listOf(1))
    assertSame(index, pruneFolders(index, listOf(3, 7)))
  }

  // ------------------------------------------------------------ diffFolderSelection

  @Test
  fun `只算改动过的夹 没动的不发请求`() {
    assertEquals(
      FolderSelectionDiff(added = listOf(9), removed = listOf(3)),
      diffFolderSelection(listOf(3, 7), listOf(7, 9)),
    )
  }

  @Test
  fun `一个都没改时两边都是空`() {
    assertEquals(
      FolderSelectionDiff(emptyList(), emptyList()),
      diffFolderSelection(listOf(3, 7), listOf(7, 3)),
    )
  }

  @Test
  fun `从没收藏过到勾了两个夹`() {
    assertEquals(
      FolderSelectionDiff(added = listOf(3, 7), removed = emptyList()),
      diffFolderSelection(emptyList(), listOf(7, 3)),
    )
  }

  @Test
  fun `全部取消`() {
    assertEquals(
      FolderSelectionDiff(added = emptyList(), removed = listOf(3, 7)),
      diffFolderSelection(listOf(3, 7), emptyList()),
    )
  }

  // ------------------------------------------------------------ parseTopicFavorIndex

  @Test
  fun `读回落盘的索引`() {
    assertEquals(mapOf(1L to listOf(3, 7)), parseTopicFavorIndex(json("""{"1":[7,3,3]}""")))
  }

  @Test
  fun `坏条目跳过 整体不炸`() {
    val raw = json(
      """{"1":[7],"0":[7],"abc":[7],"2":"不是数组","3":["不是数字"],"4":[]}""",
    )
    assertEquals(mapOf(1L to listOf(7)), parseTopicFavorIndex(raw))
  }

  @Test
  fun `不是对象时给一份空索引`() {
    assertEquals(emptyMap(), parseTopicFavorIndex(null))
    assertEquals(emptyMap(), parseTopicFavorIndex(json("[1,2]")))
    assertEquals(emptyMap(), parseTopicFavorIndex(json("null")))
  }

  @Test
  fun `一趟存读之后索引原样`() {
    val index = mapOf(1L to listOf(3, 7), 42L to listOf(9))
    assertEquals(index, parseTopicFavorIndex(index.toJson()))
  }
}
