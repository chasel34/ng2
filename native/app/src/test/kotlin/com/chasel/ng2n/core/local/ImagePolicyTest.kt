package com.chasel.ng2n.core.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 图片加载策略。用例口径照搬 RN 侧 `src/ui/network.ts` 的两个 hook 与
 * `image-viewer.tsx:66-75` 的取址分支 —— 那边没有单测(hook 跑不了),
 * 移过来正好把它钉住。
 */
class ImagePolicyTest {

  private val original = "https://img.nga.cn/attachments/mon_202608/07/a.jpg"
  private val thumbnail = "$original.thumb.jpg"

  @Test
  fun `wifiOnly 关掉时永远不折`() {
    assertEquals(true, ImagePolicy.imagesUnlocked(wifiOnly = false, metered = true))
    assertEquals(true, ImagePolicy.imagesUnlocked(wifiOnly = false, metered = false))
  }

  @Test
  fun `wifiOnly 开着时只在计费网络下折`() {
    assertEquals(false, ImagePolicy.imagesUnlocked(wifiOnly = true, metered = true))
    assertEquals(true, ImagePolicy.imagesUnlocked(wifiOnly = true, metered = false))
  }

  @Test
  fun `thumbnail 档不看网络恒拉缩略图`() {
    assertEquals(true, ImagePolicy.preferThumbnail(ImageQuality.THUMBNAIL, metered = false))
    assertEquals(true, ImagePolicy.preferThumbnail(ImageQuality.THUMBNAIL, metered = true))
  }

  @Test
  fun `original 档不看网络恒拉原图`() {
    assertEquals(false, ImagePolicy.preferThumbnail(ImageQuality.ORIGINAL, metered = false))
    assertEquals(false, ImagePolicy.preferThumbnail(ImageQuality.ORIGINAL, metered = true))
  }

  @Test
  fun `smart 档跟着网络走`() {
    assertEquals(false, ImagePolicy.preferThumbnail(ImageQuality.SMART, metered = false))
    assertEquals(true, ImagePolicy.preferThumbnail(ImageQuality.SMART, metered = true))
  }

  @Test
  fun `默认设置是 wifiOnly 开 + smart 档`() {
    val defaults = ImageSettings()
    assertEquals(true, defaults.wifiOnlyImages)
    assertEquals(ImageQuality.SMART, defaults.imageQuality)
  }

  @Test
  fun `正文图 计费网络 + wifiOnly 开 = 折叠占位`() {
    val plan = ImagePolicy.resolve(original, thumbnail, ImageQuality.SMART, wifiOnly = true, metered = true)
    assertEquals(ImagePlan.Locked, plan)
  }

  @Test
  fun `正文图 折叠态点开之后按当前档取址`() {
    val plan = ImagePolicy.resolve(
      original, thumbnail, ImageQuality.SMART, wifiOnly = true, metered = true, revealed = true,
    )
    assertEquals(ImagePlan.Show(thumbnail), plan)
  }

  @Test
  fun `正文图 wifi 下 smart 档拉原图且不给渐进占位`() {
    val plan = ImagePolicy.resolve(original, thumbnail, ImageQuality.SMART, wifiOnly = true, metered = false)
    assertEquals(ImagePlan.Show(original, null), plan)
  }

  @Test
  fun `正文图 站外图没有缩略图变体时回落原图`() {
    val outside = "https://example.com/x.png"
    val plan = ImagePolicy.resolve(outside, null, ImageQuality.THUMBNAIL, wifiOnly = false, metered = true)
    assertEquals(ImagePlan.Show(outside), plan)
  }

  @Test
  fun `查看器不看 wifiOnly —— 是用户自己点进来的`() {
    // 同一组条件下正文图会折叠,查看器照拉
    val plan = ImagePolicy.resolveViewer(original, thumbnail, ImageQuality.SMART, metered = true)
    assertEquals(thumbnail, plan.url)
  }

  @Test
  fun `查看器拉原图时缩略图当渐进占位`() {
    val plan = ImagePolicy.resolveViewer(original, thumbnail, ImageQuality.ORIGINAL, metered = true)
    assertEquals(original, plan.url)
    assertEquals(thumbnail, plan.placeholderUrl)
  }

  @Test
  fun `查看器 查看原图 按 index 覆盖省流量档`() {
    val plan = ImagePolicy.resolveViewer(
      original, thumbnail, ImageQuality.THUMBNAIL, metered = true, forceOriginal = true,
    )
    assertEquals(original, plan.url)
    assertEquals(thumbnail, plan.placeholderUrl)
  }

  @Test
  fun `查看器 缩略图地址与原图相同时不当占位`() {
    val plan = ImagePolicy.resolveViewer(original, original, ImageQuality.ORIGINAL, metered = false)
    assertEquals(original, plan.url)
    assertNull(plan.placeholderUrl)
  }
}
