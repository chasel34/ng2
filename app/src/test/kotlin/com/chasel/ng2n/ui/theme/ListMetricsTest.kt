package com.chasel.ng2n.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 票 42:版块主题列表那几档字号,与 RN 侧 `src/ui/tokens.ts` 逐值对齐。
 *
 * 票面报的是「主题标题偏大 6.5%、子版块 chip 偏大 11%」。复量对照图
 * (`acceptance/visual/03-board.png`,两半都是 50% 缩放)得到的却是反过来的一点点:
 * 同一条标题「[赛事杂谈]Insane LFT + [守望先锋赛事]」渲染宽 Expo 380px / 原生 372px,
 * 字形高两边都是 22px;「队友招募」chip 外框 Expo 99px / 原生 97px。
 * 也就是说这两档**本来就是齐的**,票面的 387/412 与 100/111 复现不出来。
 *
 * 既然是一场量错,这里就把「齐」钉死:两边的档位来源是同一张表,
 * 谁再动 `listTitle` / `topicTitle` / `listMeta`,先过这条。
 */
class ListMetricsTest {

  /** RN `tokens.ts`:`topicTitle: { fontSize: 17, lineHeight: 24.65 }`(1.45 倍) */
  @Test
  fun `主题列表屏的标题是 17`() {
    assertEquals(17f, Typo.topicTitle.size.value)
    assertEquals(24.65f, Typo.topicTitle.lineHeight.value)
    // 行高倍数照设计稿的 1.45
    assertEquals(1.45f, Typo.topicTitle.lineHeight.value / Typo.topicTitle.size.value, 0.001f)
  }

  /** RN `tokens.ts`:`listTitle: { fontSize: 16, lineHeight: 23.2 }` —— 二级列表(带时间那档) */
  @Test
  fun `二级列表的标题是 16`() {
    assertEquals(16f, Typo.listTitle.size.value)
    assertEquals(23.2f, Typo.listTitle.lineHeight.value)
  }

  /** 两档差一级:`TopicRow` 按 `model.simple` 选档,选错就是票 42 说的那 6.25% */
  @Test
  fun `两档标题差 6 个百分点`() {
    val ratio = Typo.topicTitle.size.value / Typo.listTitle.size.value
    assertEquals(1.0625f, ratio, 0.0001f)
  }

  /** meta 行与子版块 chip 共用的 12.5(RN `listMeta`) */
  @Test
  fun `meta 行与子版块 chip 是 12 点 5`() {
    assertEquals(12.5f, Typo.listMeta.size.value)
    assertEquals(18f, Typo.listMeta.lineHeight.value)
  }

  /** chip 的圆角与横向留白(RN `board/[id].tsx` 的 `subBoardTag` / `subBoardBarContent`) */
  @Test
  fun `chip 的圆角与条内留白`() {
    assertEquals(9f, Radius.sm.value)
    assertEquals(8f, Spacing.sm.value)
    assertEquals(12f, Spacing.md.value)
    assertEquals(14f, Spacing.row.value)
  }
}
