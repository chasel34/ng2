package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.bbcode.AttachmentRef

/**
 * `[album]` 里那一串图片地址的提取(RN 侧原件 `src/ui/bbcode/album.ts`)。
 *
 * 解析器把 `[album]` 的内容原样留在 `value` 里(票 09 的约定),因为相册的内容有两种写法:
 * 要么是一串 `[img]`/`[url]` 标签,要么是**裸地址**堆在一起。取法照 NGA 官方
 * `js_bbscode_core.js` 的 `[album]` 分支:里面出现过 `[img]`/`[url]` 就只认标签之间的地址,
 * 否则退回扫裸地址。
 *
 * 纯字符串处理,不碰组件,这样这套判断能单测。
 */

/** 内容里出现过 `[img]` / `[url]` 时,只认「`]` 与 `[` 之间」的地址。 */
private val TAGGED_PATTERN =
  Regex("""]\s*((?:https?://|\./)[^\[]+?)\s*\[""", RegexOption.IGNORE_CASE)

/**
 * 官方扫裸地址用的那条:地址前面必须是**串首**或一个「不属于地址」的字符。
 *
 * 不加 MULTILINE —— TS 原件那条也没有 `m` 标志,而换行本来就落在
 * 「不属于地址的字符」里,加了反而会在换行处多出一次重叠匹配的机会。
 */
private val BARE_PATTERN = Regex(
  """(?:^|[^a-zA-Z0-9\-_+=.${'$'};/?:@&#%])((?:https?://|\./)[a-zA-Z0-9\-_+=.${'$'};/?:@&#%]+)""",
  RegexOption.IGNORE_CASE,
)

private val HAS_TAG = Regex("""\[(?:img|url)]""", RegexOption.IGNORE_CASE)

/** [albumImageUrls] 内部用的最小 [AttachmentRef]——相册里的地址不是 AST 节点。 */
private data class AlbumRef(
  override val src: String,
  override val needsAttachBase: Boolean,
) : AttachmentRef

/** 相册里的每一张图,已拼好可以直接喂给图片组件。 */
fun albumImageUrls(
  value: String,
  options: AttachmentUrlOptions,
  urls: AttachmentUrls,
): List<String> {
  val pattern = if (HAS_TAG.containsMatchIn(value)) TAGGED_PATTERN else BARE_PATTERN
  return pattern.findAll(value).map { match ->
    val raw = match.groupValues[1]
    val relative = raw.startsWith("./")
    urls.attachmentUrl(
      AlbumRef(src = if (relative) raw.substring(2) else raw, needsAttachBase = relative),
      options,
    )
  }.toList()
}
