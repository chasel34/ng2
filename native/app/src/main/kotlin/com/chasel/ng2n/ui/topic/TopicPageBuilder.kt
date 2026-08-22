package com.chasel.ng2n.ui.topic

import androidx.compose.ui.graphics.Color
import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.DefaultAttachmentUrls
import com.chasel.ng2n.core.api.Floor
import com.chasel.ng2n.core.api.FloorUser
import com.chasel.ng2n.core.api.TopicDetail
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.local.DiceSeed
import com.chasel.ng2n.core.local.decodeAnonymousName
import com.chasel.ng2n.core.local.extractQuoteRefs
import com.chasel.ng2n.core.local.formatReputation
import com.chasel.ng2n.core.local.parseVote
import com.chasel.ng2n.ui.bbcode.ATTACHMENT_IMAGE_KIND
import com.chasel.ng2n.ui.bbcode.BBCodeNodeShape
import com.chasel.ng2n.ui.bbcode.BBCodeRenderOptions
import com.chasel.ng2n.ui.bbcode.CommentEntry
import com.chasel.ng2n.ui.bbcode.RenderModelBuilder
import com.chasel.ng2n.ui.bbcode.collectFloorImages
import com.chasel.ng2n.ui.bbcode.plainTextOf
import com.chasel.ng2n.ui.bbcode.resolveFloorDice
import com.chasel.ng2n.ui.bbcode.signatureRenderOptions
import kotlinx.collections.immutable.toImmutableList

/**
 * 一页 `read.php` 的结果 → [PageRenderModel]。**纯 Kotlin,不碰 composition**,
 * 所以能在 `Dispatchers.Default` 上跑、能 JVM 单测。
 *
 * 这就是 anzong 那条「数据到达时后台线程一次性转换,bind 时零计算」的落点
 * (`research/anzong.md`「顺的机制清单」①)。一页 20 楼实测:解析 + 建模在
 * JVM 上是毫秒级(票 11 探针:parse 3ms + build 5.8ms / 420 段),
 * 而这里做的每一件事在 RN 版里都发生在**渲染期**、在 JS 单线程上。
 */
object TopicPageBuilder {

  fun build(
    detail: TopicDetail,
    /** 路由带进来的 tid。骰子种子与投票分组语法都按它算,不用响应里的 —— 过滤视图下
     *  服务端偶尔不回 `__T.tid`,拿 0 当种子会把整楼点数算错 */
    tid: Long,
    style: TopicRenderStyle,
    urls: AttachmentUrls = DefaultAttachmentUrls,
  ): PageRenderModel {
    val starter = detail.floors.firstOrNull { it.isStarter }
    return PageRenderModel(
      page = detail.page,
      subject = detail.subject,
      boardName = detail.boardName,
      totalRows = detail.totalRows,
      rowsPerPage = detail.rowsPerPage,
      totalPages = detail.totalPages,
      attachBase = detail.attachBase,
      source = detail.source,
      floors = detail.floors.map { buildFloor(it, detail, tid, style, urls) }.toImmutableList(),
      hotReplies = detail.hotReplies.map { buildFloor(it, detail, tid, style, urls) }
        .toImmutableList(),
      starterName = starter?.let { detail.users[it.authorKey]?.name },
    )
  }

  private fun buildFloor(
    floor: Floor,
    detail: TopicDetail,
    tid: Long,
    style: TopicRenderStyle,
    urls: AttachmentUrls,
  ): FloorRenderItem {
    val user = detail.users[floor.authorKey]
    val nodes = parseBBCode(floor.content)
    // 骰子的点数是「整楼一条数列」算出来的(CONTEXT.md「骰子」),所以按楼算一次,
    // 渲染器只按文档顺序取第 n 个
    val dice = resolveFloorDice(
      nodes,
      DiceSeed(authorId = floor.authorId, tid = tid, pid = floor.pid),
    )
    val options = BBCodeRenderOptions(
      attachBase = detail.attachBase,
      // 发帖时间是 [noimg] 相对路径补 mon_YYYYMM/DD/ 的依据,所以按楼层给
      postedAt = floor.postedAt,
      dice = dice.toImmutableList(),
      colors = style.colors,
      bodyFontSize = style.bodyFontSize,
      bodyLineHeight = style.bodyLineHeight,
      attachmentUrls = urls,
    )

    val signature = user?.signature
      ?.takeIf { style.showSignature && it.isNotEmpty() }
      ?.let { RenderModelBuilder.build(parseBBCode(it), signatureRenderOptions(options)) }
      ?.takeUnless { it.isEmpty }

    val attachOptions = AttachmentUrlOptions(base = detail.attachBase, postedAt = floor.postedAt)
    val anonymousName = user?.rawName?.let(::decodeAnonymousName)
    val name = user?.name ?: "未知用户"

    return FloorRenderItem(
      pid = floor.pid,
      lou = floor.lou,
      recommendPid = if (floor.lou == 0L) 0L else floor.pid,
      authorKey = floor.authorKey,
      user = user,
      displayName = name,
      nameColor = anonymousName?.colors?.first?.let(::parseHexColor),
      isStarter = floor.isStarter,
      anonymous = user?.anonymous == true,
      muted = user?.muted == true,
      nuked = user?.nuked == true,
      avatarUrl = user?.avatarUrl,
      avatarColor = avatarColorFor(user?.key ?: name),
      avatarInitial = initialOf(name),
      levelText = user?.level ?: "—",
      reputationText = formatReputation(user?.reputation ?: 0.0),
      postCount = user?.postCount ?: 0L,
      postedAtText = floor.postedAtText,
      edited = floor.edited,
      client = floor.client,
      score = floor.score,
      // 主楼的标题就是主题标题,顶栏已经有了(RN 侧 `floor.lou > 0 && subject !== undefined`)
      subject = floor.subject?.takeIf { floor.lou > 0L },
      content = floor.content,
      body = RenderModelBuilder.build(nodes, options),
      signature = signature,
      comments = floor.notes.map { note -> commentEntryOf(note, detail.users) }.toImmutableList(),
      vote = floor.vote?.let { parseVote(it, tid) },
      attachmentImages = floor.attachments.filter { it.kind == ATTACHMENT_IMAGE_KIND }
        .toImmutableList(),
      attachmentFiles = floor.attachments.filterNot { it.kind == ATTACHMENT_IMAGE_KIND }
        .toImmutableList(),
      images = collectFloorImages(nodes, floor.attachments, attachOptions, urls).toImmutableList(),
      quoteRefs = extractQuoteRefs(nodes, BBCodeNodeShape).toImmutableList(),
      // 匿名楼层没有真身 uid(CONTEXT.md「匿名还原」),点了也没有资料可看
      profileUid = user?.uid?.takeIf { user.anonymous.not() },
    )
  }

  /**
   * 一条贴条。正文里常带一整段 `[b]Reply to …[/b]` 引用头,连同图片一起展开会把
   * 这一小块撑爆,所以压成纯文本一行(引用关系在回复链里看)—— 压平走
   * `plainTextOf`,而且是**在后台**压好,不在 composition 里解析 BBCode。
   */
  private fun commentEntryOf(note: Floor, users: Map<String, FloorUser>): CommentEntry =
    CommentEntry(
      id = note.pid.toString(),
      author = users[note.authorKey]?.name ?: "匿名",
      text = plainTextOf(note.content),
    )
}

/**
 * 按用户 key 稳定取一档头像占位底色(RN 侧 `ui/avatar.tsx` 的 `avatarColorFor`)。
 * 要的只是「同一个人每次都同一个颜色」,所以一个逐字符累加的弱散列足够。
 *
 * **口径必须与 RN 版逐字相同**:`hash * 31 + charCodeAt(i)` 取模 0xffffff。
 * JS 的 `charCodeAt` 是 UTF-16 码元,Kotlin 的 `Char.code` 也是,天然对上。
 */
fun avatarColorFor(key: String): Color {
  var hash = 0L
  for (char in key) hash = (hash * 31 + char.code) % 0xFFFFFF
  return AVATAR_COLORS[(hash % AVATAR_COLORS.size).toInt()]
}

/** RN 侧 `ui/tokens.ts` 的 `avatarColors`,七档。 */
private val AVATAR_COLORS = listOf(
  Color(0xFF3E6B7E),
  Color(0xFF7E5A3E),
  Color(0xFF5A6E3E),
  Color(0xFF6E3E5A),
  Color(0xFF3E5A7E),
  Color(0xFF7E6B3E),
  Color(0xFF4A4A6E),
)

/**
 * 取名字的首字(RN 侧 `ui/initial.ts`)。
 *
 * 按**码点**切而不是按码元:名字里可能有 emoji 或生僻字,按 UTF-16 码元切会劈出
 * 半个代理对,渲染成豆腐块。
 */
fun initialOf(name: String): String {
  val trimmed = name.trim()
  if (trimmed.isEmpty()) return "#"
  val first = trimmed.codePointAt(0)
  return String(Character.toChars(first))
}

/** 6 位 hex(不带 `#`)→ 颜色;解不出返回 null。 */
private fun parseHexColor(hex: String): Color? {
  val value = hex.toLongOrNull(16) ?: return null
  return Color(0xFF000000L or (value and 0xFFFFFFL))
}
