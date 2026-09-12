package com.chasel.ng2n.ui.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageZoomMathTest {

  private val viewW = 1048f
  private val viewH = 2200f

  private val minVisibleRatio = 0.85f

  private fun overlap(containerExtent: Float, drawnExtent: Float, offset: Float): Float =
    min(offset + drawnExtent / 2f, containerExtent / 2f) -
      max(offset - drawnExtent / 2f, -containerExtent / 2f)

  private fun covered(
    containerWidth: Float,
    containerHeight: Float,
    aspect: Float,
    scale: Float,
    x: Float,
    y: Float,
  ): Float {
    val drawn = fitDrawnSize(containerWidth, containerHeight, aspect)
    val ox = overlap(containerWidth, drawn.width * scale, x)
    val oy = overlap(containerHeight, drawn.height * scale, y)
    if (ox <= 0f || oy <= 0f) return 0f
    return ox * oy
  }

  private fun visibleRatio(
    containerWidth: Float,
    containerHeight: Float,
    aspect: Float,
    scale: Float,
    x: Float,
    y: Float,
  ): Float {
    val centered = covered(containerWidth, containerHeight, aspect, scale, 0f, 0f)
    if (centered <= 0f) return 0f
    return covered(containerWidth, containerHeight, aspect, scale, x, y) / centered
  }

  private fun displayed(containerExtent: Float, raw: Float, bound: Float): Float {
    val limit = overshootLimit(containerExtent)
    return rubberBand(clampRawPan(raw, bound, limit), bound, limit)
  }

  @Test
  fun `宽高比与视口一致时画满视口`() {
    val drawn = fitDrawnSize(viewW, viewH, viewW / viewH)
    assertEquals(viewW, drawn.width, 0.01f)
    assertEquals(viewH, drawn.height, 0.01f)
  }

  @Test
  fun `按宽度适配_长图超过视口并可纵向拖动`() {
    val tall = fitDrawnSize(viewW, viewH, 0.2f)
    assertEquals(viewW, tall.width, 0.01f)
    assertEquals(viewW / 0.2f, tall.height, 0.01f)
    assertEquals((tall.height - viewH) / 2f, panBounds(viewW, viewH, 0.2f, 1f).y, 0.01f)
    assertEquals(viewW / (viewH * 0.2f), widthFitScale(viewW, viewH, 0.2f), 0.01f)
    val portrait = fitDrawnSize(viewW, viewH, 0.75f)
    assertEquals(viewW, portrait.width, 0.01f)
    assertEquals(viewW / 0.75f, portrait.height, 0.01f)
    val landscape = fitDrawnSize(viewW, viewH, 16f / 9f)
    assertEquals(viewW, landscape.width, 0.01f)
    assertEquals(viewW * 9f / 16f, landscape.height, 0.01f)
  }

  @Test
  fun `宽高比还没拿到时退回容器尺寸`() {
    for (unknown in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
      val drawn = fitDrawnSize(viewW, viewH, unknown)
      assertEquals(viewW, drawn.width, 0.01f, "aspect=$unknown")
      assertEquals(viewH, drawn.height, 0.01f, "aspect=$unknown")
    }
  }

  @Test
  fun `视口还没量出来时是零尺寸`() {
    assertEquals(0f, fitDrawnSize(0f, 0f, 1.5f).width, 0.01f)
    assertEquals(0f, fitDrawnSize(viewW, 0f, 1.5f).height, 0.01f)
  }

  @Test
  fun `适配档位横向锁定_仅长图可纵向拖动`() {
    for (aspect in listOf(0.05f, 0.75f, 1f, 16f / 9f, 40f)) {
      val bounds = panBounds(viewW, viewH, aspect, 1f)
      assertEquals(0f, bounds.x, 0.01f, "aspect=$aspect")
      assertEquals(max(0f, (viewW / aspect - viewH) / 2f), bounds.y, 0.01f, "aspect=$aspect")
    }
  }

  @Test
  fun `放大之后的上界就是边缘对齐`() {
    val aspect = 0.75f
    val scale = DOUBLE_TAP_SCALE
    val drawn = fitDrawnSize(viewW, viewH, aspect)
    val bounds = panBounds(viewW, viewH, aspect, scale)
    assertEquals((drawn.width * scale - viewW) / 2f, bounds.x, 0.01f)
    assertEquals((drawn.height * scale - viewH) / 2f, bounds.y, 0.01f)
    assertTrue(bounds.x > 0f && bounds.y > 0f)
    assertEquals(1f, visibleRatio(viewW, viewH, aspect, scale, bounds.x, bounds.y), 0.001f)
    assertEquals(1f, visibleRatio(viewW, viewH, aspect, scale, -bounds.x, -bounds.y), 0.001f)
  }

  @Test
  fun `这一轴上图比视口小就锁死居中`() {
    val aspect = 12f
    val drawn = fitDrawnSize(viewW, viewH, aspect)
    assertTrue(drawn.height * DOUBLE_TAP_SCALE < viewH)
    val bounds = panBounds(viewW, viewH, aspect, DOUBLE_TAP_SCALE)
    assertEquals(0f, bounds.y, 0.01f)
    assertTrue(bounds.x > 0f)
  }

  @Test
  fun `视口没量出来时哪个方向都拖不动`() {
    val bounds = panBounds(0f, 0f, 1.5f, 4f)
    assertEquals(0f, bounds.x, 0.01f)
    assertEquals(0f, bounds.y, 0.01f)
    assertEquals(0f, overshootLimit(0f), 0.01f)
    assertEquals(0f, displayed(0f, raw = 9999f, bound = 0f), 0.01f)
  }

  @Test
  fun `边界之内原样返回`() {
    val bound = 500f
    val limit = overshootLimit(viewW)
    for (v in listOf(-500f, -300f, 0f, 120f, 500f)) {
      assertEquals(v, rubberBand(v, bound, limit), 0.01f, "v=$v")
      assertEquals(v, clampRawPan(v, bound, limit), 0.01f, "v=$v")
    }
  }

  @Test
  fun `小幅越界的斜率就是原来的阻尼系数`() {
    val bound = 500f
    val limit = overshootLimit(viewW)
    val over = 4f
    assertEquals(bound + over * EDGE_RESISTANCE, rubberBand(bound + over, bound, limit), 0.3f)
    assertEquals(-(bound + over * EDGE_RESISTANCE), rubberBand(-bound - over, bound, limit), 0.3f)
  }

  @Test
  fun `越界有渐近上限_拖到天涯海角也越不过去`() {
    val bound = 500f
    val limit = overshootLimit(viewW)
    for (over in listOf(10f, 1_000f, 100_000f, 1e9f, Float.POSITIVE_INFINITY)) {
      val out = rubberBand(bound + over, bound, limit)
      assertTrue(out <= bound + limit + 0.01f, "over=$over 越过了上限:$out")
      assertTrue(out > bound, "over=$over 应该还是拖得动的:$out")
    }
    val reachable = rubberBand(bound + limit, bound, limit)
    assertEquals(bound + limit * EDGE_RESISTANCE / (1f + EDGE_RESISTANCE), reachable, 0.01f)
    assertEquals(bound + limit, clampRawPan(1e9f, bound, limit), 0.01f)
    assertEquals(-(bound + limit), clampRawPan(Float.NEGATIVE_INFINITY, bound, limit), 0.01f)
  }

  @Test
  fun `没有越界额度时就是硬钳`() {
    assertEquals(300f, rubberBand(1e6f, 300f, 0f), 0.01f)
    assertEquals(-300f, clampRawPan(-1e6f, 300f, 0f), 0.01f)
  }

  @Test
  fun `脏数据不至于把图弹到宇宙里`() {
    val limit = overshootLimit(viewW)
    assertEquals(0f, clampRawPan(Float.NaN, 100f, limit), 0.01f)
    assertEquals(0f, rubberBand(Float.NaN, 100f, limit), 0.01f)
  }

  @Test
  fun `四个象限拼命拖都还看得见图`() {
    val aspect = 0.75f
    val scale = DOUBLE_TAP_SCALE
    val bounds = panBounds(viewW, viewH, aspect, scale)
    for (sx in listOf(-1f, 1f)) {
      for (sy in listOf(-1f, 1f)) {
        val x = displayed(viewW, sx * 1e6f, bounds.x)
        val y = displayed(viewH, sy * 1e6f, bounds.y)
        val ratio = visibleRatio(viewW, viewH, aspect, scale, x, y)
        assertTrue(ratio > minVisibleRatio, "象限($sx,$sy) 只剩 $ratio")
      }
    }
  }

  @Test
  fun `极端宽高比越界也还看得见图`() {
    for (aspect in listOf(0.02f, 0.1f, 0.5f, 1f, 3f, 20f, 60f)) {
      for (scale in listOf(0.6f, 1f, 1.5f, DOUBLE_TAP_SCALE, MAX_SCALE)) {
        val bounds = panBounds(viewW, viewH, aspect, scale)
        for (sx in listOf(-1f, 1f)) {
          for (sy in listOf(-1f, 1f)) {
            val x = displayed(viewW, sx * 1e6f, bounds.x)
            val y = displayed(viewH, sy * 1e6f, bounds.y)
            val ratio = visibleRatio(viewW, viewH, aspect, scale, x, y)
            assertTrue(
              ratio > minVisibleRatio,
              "aspect=$aspect scale=$scale 象限($sx,$sy) 只剩 $ratio",
            )
          }
        }
      }
    }
  }

  @Test
  fun `以焦点为锚缩放_焦点底下那个点不动`() {
    val focal = 200f
    val before = 40f
    val growth = 2.5f
    val after = focalZoomPan(before, focal, growth)
    assertEquals((focal - before) / 1f, (focal - after) / growth, 0.01f)
    assertEquals(before, focalZoomPan(before, before, growth), 0.01f)
    assertEquals(before, focalZoomPan(before, focal, 0f), 0.01f)
    assertEquals(before, focalZoomPan(before, focal, Float.NaN), 0.01f)
  }

  @Test
  fun `缩回适配档位时位移当场被钳掉`() {
    val aspect = 0.75f
    var raw = panBounds(viewW, viewH, aspect, DOUBLE_TAP_SCALE).x
    assertTrue(raw > 0f)
    val limit = overshootLimit(viewW)
    raw = nextRawPan(
      current = raw,
      focal = 0f,
      growth = 1f / DOUBLE_TAP_SCALE,
      pan = 0f,
      bound = panBounds(viewW, viewH, aspect, 1f).x,
      limit = limit,
    )
    assertTrue(abs(raw) <= limit + 0.01f, "缩小后残留位移 $raw")
    val ratio = visibleRatio(viewW, viewH, aspect, 1f, rubberBand(raw, 0f, limit), 0f)
    assertTrue(ratio > minVisibleRatio, "缩回适配档位后只剩 $ratio")
  }

  @Test
  fun `票55 复现_双击放大后连拖十次仍看得见图`() {
    val aspect = 16f / 9f
    val scale = DOUBLE_TAP_SCALE
    val bounds = panBounds(viewW, viewH, aspect, scale)
    val limitX = overshootLimit(viewW)
    val limitY = overshootLimit(viewH)
    var rawX = 0f
    var rawY = 0f
    repeat(10) { round ->
      repeat(30) {
        rawX = nextRawPan(rawX, 0f, 1f, -310f / 30f, bounds.x, limitX)
        rawY = nextRawPan(rawY, 0f, 1f, -400f / 30f, bounds.y, limitY)
        val ratio = visibleRatio(
          viewW,
          viewH,
          aspect,
          scale,
          rubberBand(rawX, bounds.x, limitX),
          rubberBand(rawY, bounds.y, limitY),
        )
        assertTrue(ratio > minVisibleRatio, "第 $round 次拖动中途只剩 $ratio")
      }
    }
    rawX = clampRawPan(rawX, bounds.x, 0f)
    rawY = clampRawPan(rawY, bounds.y, 0f)
    assertEquals(-bounds.x, rawX, 0.01f)
    assertEquals(0f, rawY, 0.01f)
    assertEquals(1f, visibleRatio(viewW, viewH, aspect, scale, rawX, rawY), 0.001f)
  }

  @Test
  fun `位移回到中心才算适配位`() {
    assertTrue(atFitOffset(0f, 0f))
    assertTrue(atFitOffset(0.2f, -0.3f))
    assertFalse(atFitOffset(120f, 0f))
    assertFalse(atFitOffset(0f, -80f))
  }
}
