package com.chasel.ng2n.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.di.ImageModule
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Typo
import kotlin.math.ceil
import kotlin.math.sqrt

private val ICON_SIZE = 32.dp

private const val STRIPE_PERIOD = 6f

private val STRIPE_STEP = STRIPE_PERIOD * sqrt(2f)

@Composable
fun BoardIcon(board: Board, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var failedId by remember { mutableStateOf<Long?>(null) }

  val url = board.iconUrl
  if (url == null || failedId == board.id) {
    BoardIconPlaceholder(board.name, modifier)
    return
  }

  AsyncImage(
    model = ImageRequest.Builder(context)
      .data(url)
      .memoryCachePolicy(CachePolicy.ENABLED)
      .diskCachePolicy(CachePolicy.ENABLED)
      .crossfade(ImageModule.CROSSFADE_MS)
      .build(),
    contentDescription = null,
    contentScale = ContentScale.Fit,
    onError = { failedId = board.id },
    modifier = modifier.size(ICON_SIZE).clip(CircleShape),
  )
}

@Composable
private fun BoardIconPlaceholder(name: String, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = modifier
      .size(ICON_SIZE)
      .clip(CircleShape)
      .background(colors.surface2)
      .drawBehind {
        val side = size.width
        val step = STRIPE_STEP * density
        val count = ceil(side * 2 / step).toInt()
        val stroke = 1f * density
        for (index in 0 until count) {
          val x = index * step - side / 2f
          drawLine(
            color = colors.surface,
            start = Offset(x, side),
            end = Offset(x + side, 0f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt,
          )
        }
      }
      .border(1.dp, colors.accent, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initialOf(name),
      style = TextStyle(
        fontSize = Typo.initial.size,
        lineHeight = Typo.initial.lineHeight,
        fontWeight = FontWeight.Bold,
        color = colors.fg2,
      ),
    )
  }
}

fun initialOf(name: String): String {
  val trimmed = name.trim()
  if (trimmed.isEmpty()) return "#"
  val codePoint = trimmed.codePointAt(0)
  return String(Character.toChars(codePoint))
}
