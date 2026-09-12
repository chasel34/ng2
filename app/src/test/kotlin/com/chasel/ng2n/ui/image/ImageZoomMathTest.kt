package com.chasel.ng2n.ui.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 大图查看器的边界数学(票 55)。
 *
 * 验收口径只有一条:**任意时刻视口里必须有图片内容**。真机验收(票 19 场景 8)
 * 录到过双击放大后一次单指平移就把图整体拖出视口、27 秒纯黑,所以下面既逐条钉死
 * 各象限/极端宽高比的取值,也把「视口里的图不少于居中时的 85%」这条不变量
 * 拿一堆参数组合暴力扫一遍。
 */
class ImageZoomMathTest {

  // 1080×2400 的机器,减去查看器顶栏与 16dp 内距之后大致就是这块可视区
  private val viewW = 1048f
  private val viewH = 2200f

  /** 越界之后视口里至少还要剩下的内容比例(相对居中时)。 */
  private val minVisibleRatio = 0.85f

  /** 图在这一轴上盖住了视口的多长(px);`<= 0` 表示这一轴上一个像素都看不到。 */
  private fun overlap(containerExtent: Float, drawnExtent: Float, offset: Float): Float =
    min(offset + drawnExtent / 2f, containerExtent / 2f) -
      max(offset - drawnExtent / 2f, -containerExtent / 2f)

  /** 视口里被图盖住的面积(px²)。0 = 全黑。 */
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

  /**
   * 相对**居中**时的可见比例。
   *
   * 直接看「占视口多少」是不公平的:横图放大 2.5× 仍可能只有视口高的 67%
   * (上下本来就是留白),那不是缺陷。这里量的是「比起居中,被拖丢了多少」。
   */
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

  /** 按线上实现走一帧:先钳 raw,再算显示值。 */
  private fun displayed(containerExtent: Float, raw: Float, bound: Float): Float {
    val limit = overshootLimit(containerExtent)
    return rubberBand(clampRawPan(raw, bound, limit), bound, limit)
  }

  // ---- fitDrawnSize:Fit 之后画出来多大 --------------------------------------

  @Test
  fun `宽高比与视口一致时画满视口`() {
    val drawn = fitDrawnSize(viewW, viewH, viewW / viewH)
    assertEquals(viewW, drawn.width, 0.01f)
    assertEquals(viewH, drawn.height, 0.01f)
  }

  @Test
  fun `按宽度适配_长图超过视口并可纵向拖动`() {
    // 长截图宽度撑满，完整图高超过视口。
    val tall = fitDrawnSize(viewW, viewH, 0.2f)
    assertEquals(viewW, tall.width, 0.01f)
    assertEquals(viewW / 0.2f, tall.height, 0.01f)
    assertEquals((tall.height - viewH) / 2f, panBounds(viewW, viewH, 0.2f, 1f).y, 0.01f)
    assertEquals(viewW / (viewH * 0.2f), widthFitScale(viewW, viewH, 0.2f), 0.01f)
    // 3:4 的普通竖图放进这块很高的视口:反过来是宽被卡住
    val portrait = fitDrawnSize(viewW, viewH, 0.75f)
    assertEquals(viewW, portrait.width, 0.01f)
    assertEquals(viewW / 0.75f, portrait.height, 0.01f)
    // 16:9 横图:宽撑满,高只有 1048×9/16
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

  // ---- panBounds:能拖多远 ---------------------------------------------------

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
    // 拖到上界时这一轴刚好边缘贴齐:视口仍被盖满,一点没丢
    assertEquals(1f, visibleRatio(viewW, viewH, aspect, scale, bounds.x, bounds.y), 0.001f)
    assertEquals(1f, visibleRatio(viewW, viewH, aspect, scale, -bounds.x, -bounds.y), 0.001f)
  }

  @Test
  fun `这一轴上图比视口小就锁死居中`() {
    // 极宽的横幅:放大 2.5× 之后高度仍远小于视口高 → 纵向上界 0
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

  // ---- 越界:拖得动,但拖不走 ------------------------------------------------

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
    // over 远小于 limit 时 ≈ 线性 0.55,与票 55 之前的手感一致
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
    // raw 被钳在 bound+limit,所以真正会显示出来的越界还要更小:limit×0.55/1.55
    val reachable = rubberBand(bound + limit, bound, limit)
    assertEquals(bound + limit * EDGE_RESISTANCE / (1f + EDGE_RESISTANCE), reachable, 0.01f)
    // raw 本身也被钳住:不会攒出一个「要往回拖半天才动」的巨值
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

  // ---- 四个象限的越界都不许全黑 ----------------------------------------------

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
    // 长截图(极瘦)与超宽横幅(极扁):这两档最容易把「按容器算边界」的实现打穿
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

  // ---- 焦点锚定与缩小重钳 ----------------------------------------------------

  @Test
  fun `以焦点为锚缩放_焦点底下那个点不动`() {
    val focal = 200f
    val before = 40f
    val growth = 2.5f
    val after = focalZoomPan(before, focal, growth)
    // 焦点在图上的位置(图坐标 = (focal - offset) / scale)缩放前后不变
    assertEquals((focal - before) / 1f, (focal - after) / growth, 0.01f)
    // 焦点就是图心时位移不变
    assertEquals(before, focalZoomPan(before, before, growth), 0.01f)
    // growth 非法时原样不动
    assertEquals(before, focalZoomPan(before, focal, 0f), 0.01f)
    assertEquals(before, focalZoomPan(before, focal, Float.NaN), 0.01f)
  }

  @Test
  fun `缩回适配档位时位移当场被钳掉`() {
    val aspect = 0.75f
    // 放大档位下拖到边缘
    var raw = panBounds(viewW, viewH, aspect, DOUBLE_TAP_SCALE).x
    assertTrue(raw > 0f)
    // 一帧之内捏回 1×:新边界是 0,raw 必须当场被钳,否则缩完残留一大截 → 黑边
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

  // ---- 复现回放 --------------------------------------------------------------

  @Test
  fun `票55 复现_双击放大后连拖十次仍看得见图`() {
    val aspect = 16f / 9f
    val scale = DOUBLE_TAP_SCALE
    val bounds = panBounds(viewW, viewH, aspect, scale)
    val limitX = overshootLimit(viewW)
    val limitY = overshootLimit(viewH)
    var rawX = 0f
    var rawY = 0f
    // 票里那一下是 (610,1600) → (300,1200);脚本连着跑十次,每次拆 30 帧派发
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
    // 松手:钳回边界对齐
    rawX = clampRawPan(rawX, bounds.x, 0f)
    rawY = clampRawPan(rawY, bounds.y, 0f)
    assertEquals(-bounds.x, rawX, 0.01f)
    // 这张横图放大 2.5× 之后高度仍小于视口 → 纵轴锁死居中
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
