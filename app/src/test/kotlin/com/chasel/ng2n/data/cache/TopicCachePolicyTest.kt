package com.chasel.ng2n.data.cache

import kotlin.test.Test
import kotlin.test.assertEquals

class TopicCachePolicyTest {

  private fun page(
    tid: Long,
    page: Int,
    subject: String = "主题 $tid",
    boardName: String? = null,
    favCode: String? = null,
    floors: Int = 20,
    totalPages: Int = 3,
    bytes: Long = 1000,
    usedAt: Long = 1_000,
  ) = CachedPage(tid, page, subject, boardName, favCode, floors, totalPages, bytes, usedAt)

  @Test
  fun `同一主题的页聚合成一条 字节相加 时间取最近`() {
    val topics = summarizeCachedPages(
      listOf(
        page(tid = 7, page = 2, bytes = 300, usedAt = 500),
        page(tid = 7, page = 1, bytes = 200, usedAt = 900),
      ),
    )

    assertEquals(1, topics.size)
    assertEquals(7L, topics[0].tid)
    assertEquals(listOf(1, 2), topics[0].pages)
    assertEquals(500L, topics[0].bytes)
    assertEquals(900L, topics[0].usedAt)
  }

  @Test
  fun `元数据以最近写入的那一页为准 但缺席时保留旧值`() {
    val topics = summarizeCachedPages(
      listOf(
        page(tid = 7, page = 1, subject = "旧标题", boardName = "硬件", usedAt = 100),
        page(tid = 7, page = 2, subject = "新标题", usedAt = 200),
      ),
    )

    assertEquals("新标题", topics[0].subject)
    assertEquals("硬件", topics[0].boardName)
  }

  @Test
  fun `按最近使用倒序 这就是「我的缓存」页的顺序`() {
    val topics = summarizeCachedPages(
      listOf(
        page(tid = 1, page = 1, usedAt = 100),
        page(tid = 2, page = 1, usedAt = 300),
        page(tid = 3, page = 1, usedAt = 200),
      ),
    )

    assertEquals(listOf(2L, 3L, 1L), topics.map { it.tid })
  }

  private fun topics(count: Int, bytes: Long) = summarizeCachedPages(
    (1..count).map { page(tid = it.toLong(), page = 1, bytes = bytes, usedAt = it.toLong()) },
  )

  @Test
  fun `主题数超限时从最久未用的开始淘汰`() {
    assertEquals(listOf(1L, 2L), planCacheEviction(topics(5, 100), maxTopics = 3))
  }

  @Test
  fun `字节数超限时同样按最久未用淘汰 淘汰到不超为止`() {
    assertEquals(listOf(1L, 2L), planCacheEviction(topics(4, 100), maxBytes = 250))
  }

  @Test
  fun `淘汰是整主题走的 一个主题的几页要么全留要么全删`() {
    val list = summarizeCachedPages(
      listOf(
        page(tid = 1, page = 1, bytes = 100, usedAt = 10),
        page(tid = 1, page = 2, bytes = 100, usedAt = 20),
        page(tid = 2, page = 1, bytes = 100, usedAt = 30),
      ),
    )
    assertEquals(listOf(1L), planCacheEviction(list, maxBytes = 150))
  }

  @Test
  fun `没超限时一个都不淘汰`() {
    assertEquals(emptyList(), planCacheEviction(topics(3, 100), maxTopics = 10, maxBytes = 1000))
  }

  @Test
  fun `最近用的那个主题永远留着 哪怕它自己就超过字节上限`() {
    assertEquals(listOf(1L), planCacheEviction(topics(2, 900), maxBytes = 100))
  }

  @Test
  fun `只缓存一页时顺带报楼数`() {
    assertEquals("第 1 页 · 40 楼", cachePagesLabel(listOf(1), floors = 40))
  }

  @Test
  fun `连续页压成区间`() {
    assertEquals("第 1–3 页", cachePagesLabel(listOf(1, 2, 3), floors = 20))
  }

  @Test
  fun `不连续的页分段列出来`() {
    assertEquals("第 1–2、5 页", cachePagesLabel(listOf(1, 2, 5), floors = 20))
  }

  @Test
  fun `段数太多时只列前几段 末尾报总页数`() {
    assertEquals("第 1、3、5 等 5 页", cachePagesLabel(listOf(1, 3, 5, 7, 9), floors = 20))
  }

  @Test
  fun `照设计稿的口径给出人读的大小`() {
    assertEquals("512 B", formatCacheSize(512))
    assertEquals("4 KB", formatCacheSize(4096))
    assertEquals("1.2 MB", formatCacheSize((1.2 * 1024 * 1024).toLong()))
    assertEquals("42.6 MB", formatCacheSize((42.6 * 1024 * 1024).toLong()))
    assertEquals("2.00 GB", formatCacheSize(2L * 1024 * 1024 * 1024))
  }

  @Test
  fun `与 UTF-8 编码器的结果一致 ASCII 中文 emoji 落单代理项`() {
    for (text in listOf("", "abc", "网事杂谈", "a中🀄b", "楼层😀")) {
      assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), utf8ByteLength(text), text)
    }
    assertEquals(3L, utf8ByteLength("\uD800"))
  }

  @Test
  fun `把各主题的占用加起来`() {
    val list = summarizeCachedPages(
      listOf(page(tid = 1, page = 1, bytes = 1024), page(tid = 2, page = 1, bytes = 2048)),
    )
    assertEquals(3072L, cacheTotalBytes(list))
  }
}
