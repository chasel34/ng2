package com.chasel.ng2n.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.theme.LocalNg2nColors

@Composable
fun TopicDevOpenSection(onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  var tid by rememberSaveable { mutableStateOf("47406116") }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "主题详情 demo(票 13,票 16 移除)",
      style = MaterialTheme.typography.labelLarge,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .background(colors.surface2, RoundedCornerShape(8.dp))
          .padding(horizontal = 12.dp, vertical = 10.dp)
          .semantics { contentDescription = TOPIC_DEV_INPUT_TAG },
      ) {
        BasicTextField(
          value = tid,
          onValueChange = { next -> tid = next.filter(Char::isDigit).take(12) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          textStyle = TextStyle(color = colors.fg),
          cursorBrush = SolidColor(colors.primary),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      Button(
        onClick = { tid.toLongOrNull()?.takeIf { it > 0 }?.let(onOpen) },
        modifier = Modifier.semantics { contentDescription = TOPIC_DEV_BUTTON_TAG },
      ) {
        Text("打开主题")
      }
    }
  }
}

const val TOPIC_DEV_INPUT_TAG: String = "ng2n-topic-dev-input"
const val TOPIC_DEV_BUTTON_TAG: String = "ng2n-topic-dev-open"
