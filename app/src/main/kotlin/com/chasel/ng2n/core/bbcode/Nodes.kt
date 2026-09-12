package com.chasel.ng2n.core.bbcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
sealed interface BBCodeNode : ParseNode

sealed interface ChildBearing : BBCodeNode {
  val children: List<BBCodeNode>

  fun withChildren(children: List<BBCodeNode>): BBCodeNode
}

sealed interface AttachmentRef {
  val src: String
  val needsAttachBase: Boolean
}

@Serializable
@SerialName("text")
data class TextNode(val value: String) : BBCodeNode

@Serializable
@SerialName("linebreak")
data object LineBreakNode : BBCodeNode

@Serializable
@SerialName("divider")
data object DividerNode : BBCodeNode

@Serializable
@SerialName("code")
data class CodeNode(val value: String) : BBCodeNode

@Serializable
@SerialName("mention")
data class MentionNode(val username: String) : BBCodeNode

@Serializable
@SerialName("smiley")
data class SmileyNode(val code: String) : BBCodeNode

@Serializable
@SerialName("dice")
data class DiceNode(val expression: String) : BBCodeNode

@Serializable
@SerialName("album")
data class AlbumNode(val value: String) : BBCodeNode

@Serializable
@SerialName("bold")
data class BoldNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("italic")
data class ItalicNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("underline")
data class UnderlineNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("strike")
data class StrikeNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("color")
data class ColorNode(val value: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("size")
data class SizeNode(val value: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("font")
data class FontNode(val value: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("quote")
data class QuoteNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("collapse")
data class CollapseNode(
  override val children: List<BBCodeNode>,
  val title: String? = null,
) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("align")
data class AlignNode(
  val align: Align,
  override val children: List<BBCodeNode>,
) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
enum class Align {
  @SerialName("left") LEFT,

  @SerialName("center") CENTER,

  @SerialName("right") RIGHT,
}

@Serializable
@SerialName("heading")
data class HeadingNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("box")
data class BoxNode(
  val variant: BoxVariant,
  override val children: List<BBCodeNode>,
  val punishment: Punishment? = null,
) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
enum class BoxVariant {
  @SerialName("lessernuke") LESSERNUKE,

  @SerialName("hip") HIP,

  @SerialName("item") ITEM,
}

@Serializable
enum class Punishment {
  @SerialName("post") POST,

  @SerialName("topic") TOPIC,

  @SerialName("locked") LOCKED,
}

@Serializable
@SerialName("list")
data class ListNode(
  val ordered: Boolean,
  val items: List<List<BBCodeNode>>,
) : BBCodeNode

@Serializable
data class TableCell(
  val colspan: Int,
  val rowspan: Int,
  val children: List<BBCodeNode>,
  val width: String? = null,
)

@Serializable
data class TableRow(val cells: List<TableCell>)

@Serializable
@SerialName("table")
data class TableNode(val rows: List<TableRow>) : BBCodeNode

@Serializable
@SerialName("link")
data class LinkNode(val href: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("userRef")
data class UserRefNode(val uid: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("topicRef")
data class TopicRefNode(val tid: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("floorRef")
data class FloorRefNode(
  val pid: String,
  val args: List<String>,
  override val children: List<BBCodeNode>,
) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

@Serializable
@SerialName("image")
data class ImageNode(
  override val src: String,
  override val needsAttachBase: Boolean,
  val variant: ImageVariant,
) : BBCodeNode, AttachmentRef

@Serializable
enum class ImageVariant {
  @SerialName("img") IMG,

  @SerialName("noimg") NOIMG,
}

@Serializable
@SerialName("attach")
data class AttachNode(
  override val src: String,
  override val needsAttachBase: Boolean,
) : BBCodeNode, AttachmentRef

@Serializable
@SerialName("flash")
data class FlashNode(
  override val src: String,
  override val needsAttachBase: Boolean,
  val media: FlashMedia,
) : BBCodeNode, AttachmentRef

@Serializable
enum class FlashMedia {
  @SerialName("video") VIDEO,

  @SerialName("audio") AUDIO,

  @SerialName("flash") FLASH,
}

val BBCodeJson: Json = Json

private val NODE_LIST_SERIALIZER = ListSerializer(BBCodeNode.serializer())

fun encodeBBCode(nodes: List<BBCodeNode>): JsonElement =
  BBCodeJson.encodeToJsonElement(NODE_LIST_SERIALIZER, nodes)

fun encodeBBCodeToString(nodes: List<BBCodeNode>): String =
  BBCodeJson.encodeToString(NODE_LIST_SERIALIZER, nodes)

fun decodeBBCode(json: String): List<BBCodeNode> =
  BBCodeJson.decodeFromString(NODE_LIST_SERIALIZER, json)
