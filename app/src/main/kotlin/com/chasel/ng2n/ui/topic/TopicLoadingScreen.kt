package com.chasel.ng2n.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.ui.theme.LocalNg2nColors

/** 首屏与未缓存的翻页共用；一次加载只选一句，不随转圈动画或重组更换。 */
@Composable
internal fun TopicLoadingScreen(page: Int, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val quote = remember(page) { pickLoadingQuote() }
  Box(
    modifier.fillMaxSize().background(colors.bg),
    contentAlignment = BiasAlignment(0f, 0.10f),
  ) {
    Column(
      modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      CircularProgressIndicator(
        color = colors.primary,
        strokeWidth = 5.dp,
        modifier = Modifier.size(64.dp).semantics { contentDescription = "正在加载帖子" },
      )
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = quote.text,
          color = colors.fg,
          fontSize = 16.sp,
          lineHeight = 25.sp,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          text = quote.author?.let { "— $it · 弱智吧" }
            ?: "弱智吧 · ${quote.year} 年度精选",
          color = colors.meta,
          fontSize = 12.sp,
          lineHeight = 18.sp,
          textAlign = TextAlign.End,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}
