package com.chasel.ng2n.core.ai

import org.junit.Test
import org.junit.Assert.*

class CitationsTest {
  @Test fun knownIdsBecomeSourcesAndUnknownIdsDisappear() {
    assertEquals(listOf(AnswerPart.Text("正文"), AnswerPart.Source("s1"), AnswerPart.Text("后文")),
      parseCitations("正文[[s1]][[s900]][[invalid]]后文", setOf("s1")))
  }
  @Test fun everyPartialCitationIsHiddenUntilClosingDelimiterArrives() {
    val marker = "[[s123]]"
    for (length in 1 until marker.length) {
      assertEquals(listOf(AnswerPart.Text("正文")), parseCitations("正文" + marker.take(length), setOf("s123")))
    }
    assertEquals(listOf(AnswerPart.Text("正文"), AnswerPart.Source("s123")), parseCitations("正文$marker", setOf("s123")))
    assertEquals(listOf(AnswerPart.Text("[链接](https://example.org)")), parseCitations("[链接](https://example.org)", emptySet()))
  }
  @Test fun stoppedAnswersDropDanglingMarkupButKeepEveryLineThatHasText() {
    assertEquals("结论段落", trimIncompleteMarkdown("结论段落\n\n##"))
    assertEquals("结论段落\n## 小节", trimIncompleteMarkdown("结论段落\n## 小节\n- \n**"))
    assertEquals("", trimIncompleteMarkdown("##"))
    assertEquals("## 小节\n正文", trimIncompleteMarkdown("## 小节\n正文"))
    assertEquals("- 第一条\n- 第二条", trimIncompleteMarkdown("- 第一条\n- 第二条"))
    // 围栏未闭合时末尾标记是代码内容，按字面保留。
    assertEquals("```\n##", trimIncompleteMarkdown("```\n##"))
  }

  @Test fun sheetUsesReleasePositionAndExactDesignBoundaries() {
    assertEquals(AiSheetPosition.FULL, snapAiSheet(169f / 844))
    assertEquals(AiSheetPosition.HALF, snapAiSheet(170f / 844))
    assertEquals(AiSheetPosition.HALF, snapAiSheet(560f / 844))
    assertEquals(AiSheetPosition.COLLAPSED, snapAiSheet(561f / 844))
  }
}
