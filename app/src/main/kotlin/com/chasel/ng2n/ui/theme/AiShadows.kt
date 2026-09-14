package com.chasel.ng2n.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

enum class AiShadow { HAIRLINE, BTN, CARD, RAISED, OVERLAY }

@Composable
fun Modifier.aiShadow(level: AiShadow, shape: Shape): Modifier {
  val colors = LocalNg2nColors.current
  val dark = colors == DarkColors
  val ink = if (dark) Color.Black else Color(0xFF463A18)
  val layers = when (level) {
    AiShadow.HAIRLINE -> emptyList()
    AiShadow.BTN -> if (dark) listOf(Triple(1f, 2f, .3f)) else listOf(Triple(0f, 4f, .05f))
    AiShadow.CARD -> if (dark) listOf(Triple(1f, 2f, .2f), Triple(2f, 6f, .2f)) else listOf(Triple(18f, 47f, .04f), Triple(7.5f, 19f, .025f), Triple(2.3f, 5.8f, .02f), Triple(.5f, 1.3f, .02f))
    AiShadow.RAISED -> if (dark) listOf(Triple(2f, 10f, .3f)) else listOf(Triple(17.5f, 23.4f, .07f), Triple(9.4f, 12.5f, .05f), Triple(5.25f, 7f, .03f), Triple(1.16f, 1.5f, .02f))
    AiShadow.OVERLAY -> if (dark) listOf(Triple(8f, 28f, .45f)) else listOf(Triple(25f, 50f, .09f), Triple(12f, 24f, .06f), Triple(6f, 12f, .04f), Triple(1.5f, 3f, .03f))
  }
  val ring = if (dark && level != AiShadow.HAIRLINE) Color.White.copy(alpha = when (level) {
    AiShadow.BTN -> .10f
    AiShadow.CARD -> .08f
    AiShadow.RAISED -> .11f
    AiShadow.OVERLAY -> .13f
    AiShadow.HAIRLINE -> 0f
  }) else if (level == AiShadow.BTN) colors.track else colors.divider
  return layers.fold(this) { modifier, (offset, radius, alpha) ->
    modifier.dropShadow(shape, Shadow(radius = radius.dp, color = ink.copy(alpha = alpha), offset = DpOffset(0.dp, offset.dp)))
  }.border(1.dp, ring, shape)
}
