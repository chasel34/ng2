package com.chasel.ng2n.ui.topic

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.api.FloorClient
import com.chasel.ng2n.core.api.FloorUser
import com.chasel.ng2n.core.api.TopicSource
import com.chasel.ng2n.core.local.QuoteRef
import com.chasel.ng2n.core.local.Vote
import com.chasel.ng2n.ui.bbcode.CommentEntry
import com.chasel.ng2n.ui.bbcode.FloorRenderModel
import com.chasel.ng2n.ui.bbcode.ViewerImage
import com.chasel.ng2n.ui.theme.Ng2nColors
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class PageRenderModel(
  val page: Int,
  val subject: String,
  val boardName: String?,
  val totalRows: Long,
  val rowsPerPage: Int,
  val totalPages: Int,
  val attachBase: String,
  val source: TopicSource,
  val floors: ImmutableList<FloorRenderItem>,
  val hotReplies: ImmutableList<FloorRenderItem>,
  val starterName: String?,
)

@Immutable
data class FloorRenderItem(
  val pid: Long,
  val lou: Long,
  val recommendPid: Long,
  val authorKey: String,
  val user: FloorUser?,
  val displayName: String,
  val nameColor: Color?,
  val isStarter: Boolean,
  val anonymous: Boolean,
  val muted: Boolean,
  val nuked: Boolean,
  val avatarUrl: String?,
  val avatarColor: Color,
  val avatarInitial: String,
  val levelText: String,
  val reputationText: String,
  val postCount: Long,
  val postedAtText: String,
  val edited: Boolean,
  val client: FloorClient,
  val score: Long,
  val subject: String?,
  val content: String,
  val body: FloorRenderModel,
  val signature: FloorRenderModel?,
  val comments: ImmutableList<CommentEntry>,
  val vote: Vote?,
  val attachmentImages: ImmutableList<FloorAttachment>,
  val attachmentFiles: ImmutableList<FloorAttachment>,
  val images: ImmutableList<ViewerImage>,
  val quoteRefs: ImmutableList<QuoteRef>,
  val profileUid: Long?,
) {
  val attachmentCount: Int get() = attachmentImages.size + attachmentFiles.size
}

@Immutable
data class TopicRenderStyle(
  val colors: Ng2nColors,
  val bodyFontSize: Float,
  val bodyLineHeight: Float,
  val showSignature: Boolean,
)
