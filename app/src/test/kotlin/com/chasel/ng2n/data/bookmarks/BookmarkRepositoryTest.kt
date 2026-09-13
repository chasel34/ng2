package com.chasel.ng2n.data.bookmarks

import com.chasel.ng2n.data.db.BrowseHistoryEntity
import com.chasel.ng2n.data.history.HistoryEntry
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.data.history.TopicVisit
import com.chasel.ng2n.ui.topic.FakeBrowseHistoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkRepositoryTest {

  private fun draft(
    tid: Long,
    pid: Long,
    lou: Long = pid,
    note: String? = null,
    subject: String = "主题 $tid",
    boardName: String? = "网事杂谈",
    favCode: String? = null,
    summary: String = "第 $lou 楼的正文",
  ) = BookmarkDraft(
    tid = tid,
    pid = pid,
    lou = lou,
    author = "作者",
    summary = summary,
    note = note,
    subject = subject,
    boardName = boardName,
    favCode = favCode,
  )

  @Test
  fun `分组 组间按最新创建时间倒序 组内按楼号升序`() = runTest {
    val repo = BookmarkRepository(FakeBookmarkDao())
    repo.save(draft(tid = 1, pid = 30, lou = 30), nowSeconds = 100)
    repo.save(draft(tid = 1, pid = 5, lou = 5), nowSeconds = 200)
    repo.save(draft(tid = 2, pid = 9, lou = 9), nowSeconds = 150)

    val groups = repo.observeGroups().first()
    assertEquals(listOf(1L, 2L), groups.map { it.tid })
    assertEquals(listOf(5L, 30L), groups[0].bookmarks.map { it.lou })
  }

  @Test
  fun `同一楼层再保存只改备注 创建时间保留 更新时间刷新`() = runTest {
    val repo = BookmarkRepository(FakeBookmarkDao())
    val first = repo.save(draft(tid = 1, pid = 7, note = "旧"), nowSeconds = 100)
    val second = repo.save(draft(tid = 1, pid = 7, note = "  新备注  ", summary = "改过的正文"), nowSeconds = 300)

    assertEquals(100L, first.createdAt)
    assertEquals(100L, second.createdAt)
    assertEquals(300L, second.updatedAt)
    assertEquals("新备注", second.note)
    assertEquals("第 7 楼的正文", second.summary)
    assertEquals(1, repo.observeTopic(1).first().size)
  }

  @Test
  fun `备注去首尾空白 截到 100 字 空串存为空值`() = runTest {
    val repo = BookmarkRepository(FakeBookmarkDao())
    val long = "字".repeat(120)
    assertEquals(100, repo.save(draft(tid = 1, pid = 1, note = long), 1).note?.length)
    assertNull(repo.save(draft(tid = 1, pid = 2, note = "   "), 1).note)
  }

  @Test
  fun `移除返回完整记录 恢复后原样回来 最后一条删掉后该组消失`() = runTest {
    val repo = BookmarkRepository(FakeBookmarkDao())
    repo.save(draft(tid = 1, pid = 7, note = "备注"), nowSeconds = 100)

    val removed = repo.remove(1, 7)
    assertNotNull(removed)
    assertEquals("备注", removed.note)
    assertEquals(100L, removed.createdAt)
    assertTrue(repo.observeGroups().first().isEmpty())

    repo.restore(removed)
    val back = repo.observeTopic(1).first().single()
    assertEquals(removed, back)

    assertNull(repo.remove(1, 999))
  }

  @Test
  fun `删除整组与清空全部`() = runTest {
    val repo = BookmarkRepository(FakeBookmarkDao())
    repo.save(draft(tid = 1, pid = 1), 1)
    repo.save(draft(tid = 1, pid = 2), 2)
    repo.save(draft(tid = 2, pid = 1), 3)

    repo.removeTopic(1)
    assertEquals(listOf(2L), repo.observeGroups().first().map { it.tid })

    repo.clear()
    assertEquals(0, repo.count())
  }

  @Test
  fun `元数据刷新 新值缺失时保留旧值`() = runTest {
    val repo = BookmarkRepository(FakeBookmarkDao())
    repo.save(draft(tid = 1, pid = 1, subject = "旧标题", boardName = "旧版块", favCode = "fav"), 1)

    repo.refreshTopicMeta(1, subject = "新标题", boardName = null, favCode = null)
    val group = repo.observeGroups().first().single()
    assertEquals("新标题", group.subject)
    assertEquals("旧版块", group.boardName)
    assertEquals("fav", group.favCode)

    repo.refreshTopicMeta(1, subject = "", boardName = "新版块", favCode = null)
    assertEquals("新标题", repo.observeGroups().first().single().subject)
    assertEquals("新版块", repo.observeGroups().first().single().boardName)
  }

  @Test
  fun `清空书签不影响浏览历史`() = runTest {
    val bookmarkDao = FakeBookmarkDao()
    val historyDao = FakeBrowseHistoryDao()
    val repo = BookmarkRepository(bookmarkDao)
    val history = HistoryRepository(historyDao, bookmarkDao, CoroutineScope(coroutineContext))
    history.warmUp()
    history.recordVisit(TopicVisit(tid = 1, subject = "主题 1"), nowSeconds = 10)
    repo.save(draft(tid = 1, pid = 1), 1)

    repo.clear()
    assertEquals(listOf(1L), history.entries.value.map { it.tid })
    assertEquals(1, historyDao.loadAll().size)
  }

  @Test
  fun `清空浏览历史会连受保护的一起清 书签本身保留`() = runTest {
    val bookmarkDao = FakeBookmarkDao()
    val historyDao = FakeBrowseHistoryDao()
    val repo = BookmarkRepository(bookmarkDao)
    val history = HistoryRepository(historyDao, bookmarkDao, CoroutineScope(coroutineContext))
    history.warmUp()
    repo.save(draft(tid = 1, pid = 1), 1)
    history.recordVisit(TopicVisit(tid = 1, subject = "主题 1"), nowSeconds = 10)

    history.clear()
    assertTrue(history.entries.value.isEmpty())
    assertEquals(1, repo.count())
  }

  @Test
  fun `预热不再受 200 条上限约束`() = runTest {
    val historyDao = FakeBrowseHistoryDao()
    repeat(205) { index ->
      historyDao.upsert(
        BrowseHistoryEntity(
          tid = index.toLong(),
          subject = "主题 $index",
          author = null,
          boardName = null,
          favCode = null,
          lastFloor = 0,
          maxFloor = 0,
          updatedAt = index.toLong(),
        ),
      )
    }
    val history = HistoryRepository(historyDao, FakeBookmarkDao(), CoroutineScope(coroutineContext))
    history.warmUp()
    assertEquals(205, history.entries.value.size)
  }

  @Test
  fun `组头进度合并 有历史且进度不小于 1 时带上 否则为空`() {
    val group = { tid: Long ->
      BookmarkGroup(tid = tid, subject = "主题 $tid", boardName = null, favCode = null, latestCreatedAt = 1, bookmarks = emptyList())
    }
    val rows = attachProgress(
      listOf(group(1), group(2), group(3)),
      listOf(
        HistoryEntry(tid = 1, subject = "主题 1", lastFloor = 18, updatedAt = 1),
        HistoryEntry(tid = 2, subject = "主题 2", lastFloor = 0, updatedAt = 1),
      ),
    )
    assertEquals(listOf(18L, null, null), rows.map { it.lastFloor })
  }

  @Test
  fun `摘要压缩空白截 80 字 纯图片记图片 空正文记占位`() {
    assertEquals("a b c", bookmarkSummary("  a \n\n b\t c ", hasImages = false))
    assertEquals(80, bookmarkSummary("字".repeat(100), hasImages = true).length)
    assertEquals(IMAGE_ONLY_SUMMARY, bookmarkSummary("  ", hasImages = true))
    assertEquals(EMPTY_SUMMARY, bookmarkSummary("", hasImages = false))
  }
}
