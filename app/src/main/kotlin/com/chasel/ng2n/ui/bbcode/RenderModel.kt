package com.chasel.ng2n.ui.bbcode

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.chasel.ng2n.core.bbcode.Align
import com.chasel.ng2n.core.bbcode.BoxVariant
import com.chasel.ng2n.core.local.DiceOutcome
import com.chasel.ng2n.core.local.QuoteRef
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class FloorRenderModel(val segments: ImmutableList<RenderSegment>) {
  val isEmpty: Boolean get() = segments.isEmpty()
}

@Immutable
sealed interface RenderSegment

@Immutable
data class TextSegment(
  val text: AnnotatedString,
  val smilies: ImmutableList<SmileyPlacement>,
  val fontSize: TextUnit,
  val lineHeight: TextUnit,
  val color: Color,
  val textAlign: TextAlign? = null,
) : RenderSegment

@Immutable
data class SmileyPlacement(val id: String, val url: String, val aspect: Float)

@Immutable
data class QuoteSegment(
  val body: FloorRenderModel,
  val chain: QuoteRef? = null,
  val replyHeader: Boolean = false,
  val preview: FloorRenderModel? = null,
) : RenderSegment

@Immutable
data class ImageSegment(val url: String, val thumbnailUrl: String?) : RenderSegment

@Immutable
data object DividerSegment : RenderSegment

@Immutable
data class HeadingSegment(val body: FloorRenderModel) : RenderSegment

@Immutable
data class AlignSegment(val align: Align, val body: FloorRenderModel) : RenderSegment

@Immutable
data class CollapseSegment(val title: String, val body: FloorRenderModel) : RenderSegment

@Immutable
data class BoxSegment(
  val variant: BoxVariant,
  val notice: String?,
  val body: FloorRenderModel,
) : RenderSegment

@Immutable
data class ListSegment(
  val ordered: Boolean,
  val items: ImmutableList<FloorRenderModel>,
) : RenderSegment

@Immutable
data class TableSegment(val rows: ImmutableList<TableRowModel>) : RenderSegment

@Immutable
data class TableRowModel(
  val cells: ImmutableList<TableCellModel>,
  val paddingCells: Int,
)

@Immutable
data class TableCellModel(val width: Dp, val body: FloorRenderModel)

@Immutable
data class DiceSegment(val expression: String, val outcome: DiceOutcome?) : RenderSegment

@Immutable
data class MediaSegment(
  val url: String,
  val label: String,
  val fileName: String,
) : RenderSegment

@Immutable
data class AttachSegment(val url: String, val fileName: String) : RenderSegment

@Immutable
data class AlbumSegment(val images: ImmutableList<ImageSegment>) : RenderSegment

@Immutable
data class GroupSegment(val body: FloorRenderModel) : RenderSegment

object BBCodeAnnotation {
  const val LINK: String = "ng2n:link"

  const val USER: String = "ng2n:uid"

  const val TOPIC: String = "ng2n:tid"

  const val FLOOR: String = "ng2n:pid"

  const val MENTION: String = "ng2n:mention"

  const val SPOILER: String = "ng2n:spoiler"
}
