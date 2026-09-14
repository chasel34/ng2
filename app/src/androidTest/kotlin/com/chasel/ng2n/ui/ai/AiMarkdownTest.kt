package com.chasel.ng2n.ui.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chasel.ng2n.core.ai.AiSource
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiMarkdownTest {
  @get:Rule val compose = createComposeRule()

  @Test fun fencedCodePreservesBlockAndInlineMarkdownCharacters() {
    val lines = listOf("# comment", "> quoted", "- item", "* item", "  - nested", "**bold**", "[link](https://example.org)", "| a | b |")
    compose.setContent {
      Ng2nTheme {
        AiMarkdown("```python\n" + lines.joinToString("\n") + "\n```\n# Heading\n- List", emptyList(), {})
      }
    }
    lines.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    compose.onNodeWithText("Heading").assertExists()
    compose.onNodeWithText("• List").assertExists()
  }

  @Test fun unclosedEmphasisIsShownLiterallyInsteadOfSwallowingTheRestOfTheLine() {
    compose.setContent { Ng2nTheme { AiMarkdown("**2. 亲历样本扩展（新）\n**成对的**仍然加粗", emptyList(), {}) } }
    compose.onNodeWithText("**2. 亲历样本扩展（新）").assertIsDisplayed()
    compose.onNodeWithText("成对的仍然加粗").assertIsDisplayed()
  }

  @Test fun inlineCodeIsLiteralWhileSourceValidationStillApplies() {
    val source = AiSource("s1", 42, 74, 74, 4, "甲", "今天", "来源", emptyList())
    var selected: AiSource? = null
    compose.setContent {
      Ng2nTheme {
        AiMarkdown("`**bold** *italic* [link](https://example.org)`\n```\n# reference [[s1]][[s99]]\n```\n`literal [[s99]]`", listOf(source)) { selected = it }
      }
    }
    compose.onNodeWithText("**bold** *italic* [link](https://example.org)").assertIsDisplayed()
    compose.onNodeWithText("literal ").assertExists()
    compose.onNodeWithText("74 楼").performClick()
    compose.runOnIdle { assertEquals(source, selected) }
  }
}
