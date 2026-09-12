package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.bbcode.unescapeNgaText

/**
 * 服务端错误说明里夹带的 HTML。直译 `src/core/net/server-text.ts`。
 *
 * NGA 的 `error` 字段本来是给网页版直接塞进 innerHTML 的,业务错误里带着 `<br/>`
 * 与指向 `nuke.php` 的 `<a>`,例如 51 待审核:
 *
 * ```
 * 51:帖子正等待审核;<br/><a href='/nuke.php?…' style='color:dimgray'>[查看所需的权限/条件]</a>
 * ```
 *
 * 错误页是纯文本,不剥的话标签连属性一起逐字显示(RN 版 M3 验收缺陷 2)。这里只做
 * 「摊平成纯文本」这一件事:`<br/>` 是换行,`<a>` 只留里面那句话,其余标签丢掉。
 * 不进 BBCode 解析器——这是 HTML 不是 BBCode,且错误说明不需要富文本。
 */

/** JS 正则 `\s` 的字符类(见 [isJsWhitespace]);直接写 `\s` 会掉 NBSP 与全角空格。 */
private const val JS_WS = "\\u0009\\u000a\\u000b\\u000c\\u000d\\u0020" +
  "\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"

/** `<br>` / `<br/>` / `<br />` 都是同一个东西。 */
private val BR_TAG = Regex("<br[$JS_WS]*/?>", RegexOption.IGNORE_CASE)

/**
 * 标签名必须以 ASCII 字母开头:错误说明里也会出现《`<第六感>`》这种拿尖括号当引号的
 * 写法,那不是标签,不能吃掉。
 */
private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")

/** 同一行里的连续空白(含 `&nbsp;` 解出来的那种)压成一个空格。 */
private val INLINE_SPACES = Regex("[${JS_WS.replace("\\u000a", "")}]+")

/** 剥完标签常剩下连片空行(`<br/><br/><div>` 这类),最多留一个。 */
private val BLANK_LINES = Regex("\n{3,}")

/**
 * 把服务端说明摊平成能直接上屏的纯文本。
 * 先剥标签再解实体:反过来的话,正文里写成 `&lt;b&gt;` 的字面尖括号会被当成标签吃掉。
 */
fun stripServerHtml(raw: String): String {
  val text = unescapeNgaText(HTML_TAG.replace(BR_TAG.replace(raw, "\n"), ""))
  return BLANK_LINES.replace(
    text.split("\n").joinToString("\n") { line -> INLINE_SPACES.replace(line, " ").jsTrim() },
    "\n\n",
  ).jsTrim()
}
