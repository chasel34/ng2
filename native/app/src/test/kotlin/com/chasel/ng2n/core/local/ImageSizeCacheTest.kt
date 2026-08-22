package com.chasel.ng2n.core.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 图片尺寸记忆表。用例逐条对着 RN 侧 `src/ui/bbcode/image-size.test.ts` 移过来
 * (那边 15 条,这里补了「快照顺序」与「防抖窗口不被推迟」两条)。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageSizeCacheTest {

  private class FakeStore(
    private val initial: List<Pair<String, ImageSize>> = emptyList(),
  ) : ImageSizeStore {
    val saves = mutableListOf<List<Pair<String, ImageSize>>>()
    override suspend fun load(): List<Pair<String, ImageSize>> = initial
    override suspend fun save(entries: List<Pair<String, ImageSize>>) {
      saves += entries
    }
  }

  @Test
  fun `记过的图第二次直接查得到`() = runTest {
    val cache = ImageSizeCache(backgroundScope)
    cache.remember("a.jpg", ImageSize(1200, 800))
    assertEquals(ImageSize(1200, 800), cache.sizeOf("a.jpg"))
  }

  @Test
  fun `没见过的图返回 null`() = runTest {
    assertNull(ImageSizeCache(backgroundScope).sizeOf("never.jpg"))
  }

  @Test
  fun `缩略图和原图各记各的`() = runTest {
    val cache = ImageSizeCache(backgroundScope)
    cache.remember("a.jpg", ImageSize(1200, 800))
    cache.remember("a.jpg.thumb.jpg", ImageSize(120, 80))
    assertEquals(1200, cache.sizeOf("a.jpg")?.width)
    assertEquals(120, cache.sizeOf("a.jpg.thumb.jpg")?.width)
  }

  @Test
  fun `宽高有一边是 0 的不记(解码失败)`() = runTest {
    val cache = ImageSizeCache(backgroundScope)
    cache.remember("bad.jpg", ImageSize(0, 800))
    cache.remember("bad2.jpg", ImageSize(800, 0))
    assertNull(cache.sizeOf("bad.jpg"))
    assertNull(cache.sizeOf("bad2.jpg"))
  }

  @Test
  fun `到上限后丢最早的一条,新的照记`() = runTest {
    val cache = ImageSizeCache(backgroundScope)
    repeat(IMAGE_SIZE_LIMIT) { index ->
      cache.remember("img-$index.jpg", ImageSize(100, 100))
    }
    cache.remember("newest.jpg", ImageSize(10, 20))

    assertNull(cache.sizeOf("img-0.jpg"))
    assertNotNull(cache.sizeOf("img-1.jpg"))
    assertEquals(ImageSize(10, 20), cache.sizeOf("newest.jpg"))
    assertEquals(IMAGE_SIZE_LIMIT, cache.snapshot().size)
  }

  @Test
  fun `重复记同一张不占新坑也不挪位置`() = runTest {
    val cache = ImageSizeCache(backgroundScope, limit = 3)
    cache.remember("a.jpg", ImageSize(100, 100))
    cache.remember("b.jpg", ImageSize(100, 100))
    repeat(600) { cache.remember("a.jpg", ImageSize(200, 100)) }
    cache.remember("c.jpg", ImageSize(100, 100))
    cache.remember("d.jpg", ImageSize(100, 100))

    // 表满之后丢的是最早**插入**的 a,而不是最久没被写的 b —— 与 RN 的 Map 同序
    assertNull(cache.sizeOf("a.jpg"))
    assertNotNull(cache.sizeOf("b.jpg"))
    assertEquals(listOf("b.jpg", "c.jpg", "d.jpg"), cache.snapshot().map { it.first })
  }

  @Test
  fun `warmUp 回灌存量,本会话已量到的优先`() = runTest {
    val store = FakeStore(
      listOf(
        "old.jpg" to ImageSize(100, 200),
        "fresh.jpg" to ImageSize(1, 1),
        "broken.jpg" to ImageSize(0, 5),
      ),
    )
    val cache = ImageSizeCache(backgroundScope, store)
    cache.remember("fresh.jpg", ImageSize(300, 300))
    cache.warmUp()

    assertEquals(ImageSize(100, 200), cache.sizeOf("old.jpg"))
    assertEquals(ImageSize(300, 300), cache.sizeOf("fresh.jpg"))
    assertNull(cache.sizeOf("broken.jpg"))
  }

  @Test
  fun `记尺寸后延迟合并写回,一秒内多次只写一次`() = runTest {
    val store = FakeStore()
    val cache = ImageSizeCache(backgroundScope, store)
    cache.remember("a.jpg", ImageSize(10, 10))
    cache.remember("b.jpg", ImageSize(20, 20))
    runCurrent()
    assertEquals(0, store.saves.size)

    advanceTimeBy(IMAGE_SIZE_SAVE_DEBOUNCE_MS + 100)
    runCurrent()
    assertEquals(1, store.saves.size)
    assertEquals(
      listOf("a.jpg" to ImageSize(10, 10), "b.jpg" to ImageSize(20, 20)),
      store.saves[0],
    )
  }

  @Test
  fun `防抖是批窗口不是静默期 —— 持续写也会按时落一次`() = runTest {
    val store = FakeStore()
    val cache = ImageSizeCache(backgroundScope, store)
    // 每 300ms 记一张,连续 1.2s。静默期防抖会一直被推迟到最后;批窗口按时落盘
    repeat(4) { index ->
      cache.remember("img-$index.jpg", ImageSize(10, 10))
      advanceTimeBy(300)
      runCurrent()
    }
    assertEquals(1, store.saves.size)

    // 上一批落完之后再记,开新窗口
    cache.remember("later.jpg", ImageSize(30, 30))
    advanceTimeBy(IMAGE_SIZE_SAVE_DEBOUNCE_MS + 100)
    runCurrent()
    assertEquals(2, store.saves.size)
    assertTrue(store.saves[1].any { it.first == "later.jpg" })
  }

  @Test
  fun `revision 只在真的记下新尺寸时前进`() = runTest {
    val cache = ImageSizeCache(backgroundScope)
    assertEquals(0L, cache.revision.value)
    cache.remember("a.jpg", ImageSize(10, 10))
    assertEquals(1L, cache.revision.value)
    cache.remember("a.jpg", ImageSize(10, 10))
    assertEquals(1L, cache.revision.value)
    cache.remember("a.jpg", ImageSize(20, 10))
    assertEquals(2L, cache.revision.value)
    cache.remember("bad.jpg", ImageSize(0, 10))
    assertEquals(2L, cache.revision.value)
  }
}

/** 长图判据(=「会不会被比例封顶裁掉一截」)。 */
class LongImageTest {

  @Test
  fun `横图和方图都不算`() {
    assertEquals(false, isLongImage(ImageSize(1200, 800)))
    assertEquals(false, isLongImage(ImageSize(800, 800)))
  }

  @Test
  fun `竖一点但没到封顶的不算(这张不会被裁)`() {
    assertEquals(false, isLongImage(ImageSize(900, 1200)))
  }

  @Test
  fun `1比2 的账单截图算 —— 原案的 1比3 会把它漏掉`() {
    assertEquals(true, isLongImage(ImageSize(750, 1500)))
  }

  @Test
  fun `1比10 的聊天记录长截图算`() {
    assertEquals(true, isLongImage(ImageSize(750, 7500)))
  }

  @Test
  fun `阈值就在封顶那一刀上`() {
    assertEquals(false, isLongImage(ImageSize((CONTENT_IMAGE_MIN_ASPECT * 1000).toInt(), 1000)))
    assertEquals(true, isLongImage(ImageSize((CONTENT_IMAGE_MIN_ASPECT * 1000).toInt() - 1, 1000)))
  }

  @Test
  fun `宽高有一边是 0(解码失败)不当长图`() {
    assertEquals(false, isLongImage(ImageSize(0, 1000)))
    assertEquals(false, isLongImage(ImageSize(100, 0)))
  }
}
