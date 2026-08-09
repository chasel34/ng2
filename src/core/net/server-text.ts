/**
 * 服务端错误说明里夹带的 HTML。
 *
 * NGA 的 `error` 字段本来是给网页版直接塞进 innerHTML 的，业务错误里带着 `<br/>`
 * 与指向 `nuke.php` 的 `<a>`，例如 51 待审核：
 *
 * ```
 * 51:帖子正等待审核;<br/><a href='/nuke.php?…' style='color:dimgray'>[查看所需的权限/条件]</a>
 * ```
 *
 * 错误页是纯 `Text`，不剥的话标签连属性一起逐字显示（M3 验收缺陷 2）。这里只做
 * 「摊平成纯文本」这一件事：`<br/>` 是换行，`<a>` 只留里面那句话，其余标签丢掉。
 * 不进 BBCode 解析器——这是 HTML 不是 BBCode，且错误说明不需要富文本。
 */

import { unescapeNgaText } from '../bbcode'

/** `<br>` / `<br/>` / `<br />` 都是同一个东西。 */
const BR_TAG = /<br\s*\/?>/gi

/**
 * 标签名必须以 ASCII 字母开头：错误说明里也会出现《`<第六感>`》这种拿尖括号当引号的
 * 写法，那不是标签，不能吃掉。
 */
const HTML_TAG = /<\/?[a-zA-Z][^>]*>/g

/** 同一行里的连续空白（含 `&nbsp;` 解出来的那种）压成一个空格。 */
const INLINE_SPACES = /[^\S\n]+/g

/** 剥完标签常剩下连片空行（`<br/><br/><div>` 这类），最多留一个。 */
const BLANK_LINES = /\n{3,}/g

/**
 * 把服务端说明摊平成能直接放进 `Text` 的纯文本。
 * 先剥标签再解实体：反过来的话，正文里写成 `&lt;b&gt;` 的字面尖括号会被当成标签吃掉。
 */
export function stripServerHtml(raw: string): string {
  const text = unescapeNgaText(raw.replace(BR_TAG, '\n').replace(HTML_TAG, ''))
  return text
    .split('\n')
    .map((line) => line.replace(INLINE_SPACES, ' ').trim())
    .join('\n')
    .replace(BLANK_LINES, '\n\n')
    .trim()
}
