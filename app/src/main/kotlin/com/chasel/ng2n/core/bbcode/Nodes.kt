package com.chasel.ng2n.core.bbcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 楼层正文 BBCode 的 AST 节点(ADR-0001)。直译 `src/core/bbcode/types.ts` 的 29 种节点。
 *
 * 每个节点都是 `@Serializable` 数据类,类鉴别器就叫 `type`、取值与 TS 的 `type` 字段
 * **同名同值**——所以 `BBCodeJson.encodeToJsonElement(...)` 出来的 JSON 与金样本的
 * `expected` 逐字段相同,序列化产物也能直接进 Room 帖子缓存(票 13/14)。
 *
 * 可选字段的口径按金样本:**缺席**(`collapse` 无标题、`td` 无 width、非 lessernuke 的 box)
 * 而不是 `null`。这由 `Json` 默认的 `encodeDefaults = false` + 属性默认值 `null` 保证,
 * 所以这几个字段的默认值**不许**改掉。
 *
 * TS 里 `bold/italic/underline/strike` 共用一个 `StyleNode`(靠 `type` 区分),
 * Kotlin 的密封层级里拆成四个类——JSON 形态不变。
 */
@Serializable
sealed interface BBCodeNode : ParseNode

/**
 * 有 `children` 的节点。对应 TS `internal.ts` 的 `hasChildren`(`'children' in node`)。
 *
 * `list` 的内容在 `items`、`table` 的在 `rows`,**不算**在内——归一化只重跑 `children`,
 * 那两种容器的内容在各自的 builder 里已经归一化过了。遍历用 [childNodeLists]。
 */
sealed interface ChildBearing : BBCodeNode {
  val children: List<BBCodeNode>

  /** 归一化后重建自己(TS 的 `{ ...node, children: normalize(node.children) }`)。 */
  fun withChildren(children: List<BBCodeNode>): BBCodeNode
}

/** 指向 NGA 附件空间的资源。`needsAttachBase` = `src` 是相对路径,渲染层要拼附件域名。 */
sealed interface AttachmentRef {
  val src: String
  val needsAttachBase: Boolean
}

// ---------------------------------------------------------------------------
// 叶子
// ---------------------------------------------------------------------------

/** 纯文本。已完成两轮实体解码,且不含换行——换行是 [LineBreakNode]。 */
@Serializable
@SerialName("text")
data class TextNode(val value: String) : BBCodeNode

/** 一次换行。来自 `<br/>`、`\n` 或 `\r\n`。 */
@Serializable
@SerialName("linebreak")
data object LineBreakNode : BBCodeNode

/** 独占一行的 `======` 分割线。 */
@Serializable
@SerialName("divider")
data object DividerNode : BBCodeNode

/** `[code]`。内容原样保留(含换行),不解析内部标签。 */
@Serializable
@SerialName("code")
data class CodeNode(val value: String) : BBCodeNode

/** `[@用户名]`。 */
@Serializable
@SerialName("mention")
data class MentionNode(val username: String) : BBCodeNode

/**
 * `[s:分类:名称]` 与默认套的 `[s:数字]`。
 *
 * `code` 是 `[s:` 与 `]` 之间的**原文**(不做实体解码)。怎么切分类与名称、查不到时
 * 怎么兜底,全归表情表那一层(票 12),解析器不重复实现。
 */
@Serializable
@SerialName("smiley")
data class SmileyNode(val code: String) : BBCodeNode

/** `[dice]1d100[/dice]` 或 `[dice XdY]`。结果由 `core/local` 按 NGA 伪随机复算(票 10)。 */
@Serializable
@SerialName("dice")
data class DiceNode(val expression: String) : BBCodeNode

/** `[album]`。内容原样保留,由渲染层决定相册展开方式。 */
@Serializable
@SerialName("album")
data class AlbumNode(val value: String) : BBCodeNode

// ---------------------------------------------------------------------------
// 行内样式
// ---------------------------------------------------------------------------

/** `[b]`。 */
@Serializable
@SerialName("bold")
data class BoldNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[i]`。 */
@Serializable
@SerialName("italic")
data class ItalicNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[u]`。 */
@Serializable
@SerialName("underline")
data class UnderlineNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[del]`。 */
@Serializable
@SerialName("strike")
data class StrikeNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[color=red]`。`value` 原样保留 NGA 的 26 色名或色值,由渲染层查表。 */
@Serializable
@SerialName("color")
data class ColorNode(val value: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[size=120%]`。`value` 原样保留百分比串。 */
@Serializable
@SerialName("size")
data class SizeNode(val value: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[font=宋体]`。渲染层通常忽略字体只渲染 children。 */
@Serializable
@SerialName("font")
data class FontNode(val value: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

// ---------------------------------------------------------------------------
// 引用与块级容器
// ---------------------------------------------------------------------------

/** `[quote]`。引用块,内部常嵌 `[pid]`/`[uid]` 表示被引用的楼层与作者。 */
@Serializable
@SerialName("quote")
data class QuoteNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[collapse]` / `[collapse=标题]` 折叠块。无标题时 `title` **缺席**,不是 `null`。 */
@Serializable
@SerialName("collapse")
data class CollapseNode(
  override val children: List<BBCodeNode>,
  val title: String? = null,
) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[align=center]`,以及等价的 `[l]`(左)/`[r]`(右)。 */
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

/** `[h]` 与 `===标题===`。 */
@Serializable
@SerialName("heading")
data class HeadingNode(override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[lessernuke]`(版规警告块)、`[hip]`、`[item]`——统一成带标记的容器。 */
@Serializable
@SerialName("box")
data class BoxNode(
  val variant: BoxVariant,
  override val children: List<BBCodeNode>,
  /**
   * 只有 `lessernuke` 有(其余 variant 下**缺席**):被处罚的是哪一种。标签写成
   * `[lessernuke2]` 这样带一位数字,官方 `ubbcode.lesserNuke` 按它换提示语,
   * 不带数字等同 [Punishment.POST]。
   */
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

// ---------------------------------------------------------------------------
// 列表与表格(内容不在 children 里)
// ---------------------------------------------------------------------------

/** `[list]` + `[*]`。`ordered` 对应 `[list=1]` 这类带参形式。 */
@Serializable
@SerialName("list")
data class ListNode(
  val ordered: Boolean,
  val items: List<List<BBCodeNode>>,
) : BBCodeNode

/** `[td colspan=2 rowspan=3 width=100]`。`width` 缺省时**缺席**。 */
@Serializable
data class TableCell(
  val colspan: Int,
  val rowspan: Int,
  val children: List<BBCodeNode>,
  val width: String? = null,
)

@Serializable
data class TableRow(val cells: List<TableCell>)

/** `[table]`。ADR-0001 已定简化渲染:忽略 rowspan、整表横向滚动。 */
@Serializable
@SerialName("table")
data class TableNode(val rows: List<TableRow>) : BBCodeNode

// ---------------------------------------------------------------------------
// 链接与引用
// ---------------------------------------------------------------------------

/** `[url]http://x[/url]` 或 `[url=http://x]文字[/url]`。children 为空时渲染 href。 */
@Serializable
@SerialName("link")
data class LinkNode(val href: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[uid]123[/uid]` / `[uid=123]名字[/uid]`。 */
@Serializable
@SerialName("userRef")
data class UserRefNode(val uid: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/** `[tid]123[/tid]` / `[tid=123]标题[/tid]`。 */
@Serializable
@SerialName("topicRef")
data class TopicRefNode(val tid: String, override val children: List<BBCodeNode>) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

/**
 * 指向某个楼层的 `[pid]`。`[pid=a,b,c]Reply[/pid]` 指向 `read.php?searchpost=1&pid=a`,
 * 所以 `pid` 取第一个参数;`args` 保留全部参数,回复链(票 10/13)要用后面的 tid 与页码。
 */
@Serializable
@SerialName("floorRef")
data class FloorRefNode(
  val pid: String,
  val args: List<String>,
  override val children: List<BBCodeNode>,
) : ChildBearing {
  override fun withChildren(children: List<BBCodeNode>) = copy(children = children)
}

// ---------------------------------------------------------------------------
// 附件空间资源
// ---------------------------------------------------------------------------

/** `[img]` 与 `[noimg]`。`noimg` 的路径还要按发帖日期补 `mon_YYYYMM/DD/` 前缀。 */
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

/** `[attach]`。 */
@Serializable
@SerialName("attach")
data class AttachNode(
  override val src: String,
  override val needsAttachBase: Boolean,
) : BBCodeNode, AttachmentRef

/** `[flash]` / `[flash=video]` / `[flash=audio]`。ADR-0001 定为媒体卡片外跳,不内联播放。 */
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

// ---------------------------------------------------------------------------
// 序列化入口
// ---------------------------------------------------------------------------

/**
 * AST 的 JSON 形态(帖子缓存与金样本对拍共用同一个配置)。
 *
 * - `encodeDefaults = false`(默认值)——可选字段落成「缺席」而不是 `null`,与金样本一致;
 * - `classDiscriminator`(默认)就是 `type`,与 TS 的判别字段同名。
 */
val BBCodeJson: Json = Json

private val NODE_LIST_SERIALIZER = ListSerializer(BBCodeNode.serializer())

/** AST → `JsonElement`。金样本对拍与 Room 缓存都走这一条。 */
fun encodeBBCode(nodes: List<BBCodeNode>): JsonElement =
  BBCodeJson.encodeToJsonElement(NODE_LIST_SERIALIZER, nodes)

/** AST → JSON 文本。 */
fun encodeBBCodeToString(nodes: List<BBCodeNode>): String =
  BBCodeJson.encodeToString(NODE_LIST_SERIALIZER, nodes)

/** JSON 文本 → AST(帖子缓存回读)。 */
fun decodeBBCode(json: String): List<BBCodeNode> =
  BBCodeJson.decodeFromString(NODE_LIST_SERIALIZER, json)
