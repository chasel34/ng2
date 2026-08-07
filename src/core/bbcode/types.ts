/**
 * 楼层正文 BBCode 的 AST 节点(ADR-0001)。
 *
 * 所有节点都是可 JSON 序列化的普通对象:没有函数、没有 undefined 之外的特殊值,
 * 可以直接塞进帖子缓存。渲染层 `src/ui` 对 `type` 做穷尽 switch。
 */

/** 纯文本。已完成两轮实体解码,且不含换行——换行是 `linebreak` 节点。 */
export interface TextNode {
  readonly type: 'text'
  readonly value: string
}

/** 一次换行。来自 `<br/>`、`\n` 或 `\r\n`。 */
export interface LineBreakNode {
  readonly type: 'linebreak'
}

/** 无参数的行内样式:`[b] [i] [u] [del]`。 */
export interface StyleNode {
  readonly type: 'bold' | 'italic' | 'underline' | 'strike'
  readonly children: readonly BBCodeNode[]
}

/** `[color=red]`。`value` 原样保留 NGA 的 26 色名或色值,由渲染层查表。 */
export interface ColorNode {
  readonly type: 'color'
  readonly value: string
  readonly children: readonly BBCodeNode[]
}

/** `[size=120%]`。`value` 原样保留百分比串。 */
export interface SizeNode {
  readonly type: 'size'
  readonly value: string
  readonly children: readonly BBCodeNode[]
}

/** `[font=宋体]`。渲染层通常忽略字体只渲染 children。 */
export interface FontNode {
  readonly type: 'font'
  readonly value: string
  readonly children: readonly BBCodeNode[]
}

/** `[quote]`。引用块,内部常嵌 `[pid]`/`[uid]` 表示被引用的楼层与作者。 */
export interface QuoteNode {
  readonly type: 'quote'
  readonly children: readonly BBCodeNode[]
}

/** `[code]`。内容原样保留(含换行),不解析内部标签。 */
export interface CodeNode {
  readonly type: 'code'
  readonly value: string
}

/** `[collapse]` / `[collapse=标题]` 折叠块。 */
export interface CollapseNode {
  readonly type: 'collapse'
  readonly title?: string
  readonly children: readonly BBCodeNode[]
}

/** `[list]` + `[*]`。`ordered` 对应 `[list=1]` 这类带参形式。 */
export interface ListNode {
  readonly type: 'list'
  readonly ordered: boolean
  readonly items: readonly (readonly BBCodeNode[])[]
}

/** `[td colspan=2 rowspan=3 width=100]`。 */
export interface TableCell {
  readonly colspan: number
  readonly rowspan: number
  readonly width?: string
  readonly children: readonly BBCodeNode[]
}

export interface TableRow {
  readonly cells: readonly TableCell[]
}

/** `[table]`。ADR-0001 已定简化渲染:忽略 rowspan、整表横向滚动。 */
export interface TableNode {
  readonly type: 'table'
  readonly rows: readonly TableRow[]
}

/** `[align=center]`,以及等价的 `[l]`(左)/`[r]`(右)。 */
export interface AlignNode {
  readonly type: 'align'
  readonly align: 'left' | 'center' | 'right'
  readonly children: readonly BBCodeNode[]
}

/** `[h]` 与 `===标题===`。 */
export interface HeadingNode {
  readonly type: 'heading'
  readonly children: readonly BBCodeNode[]
}

/** 独占一行的 `======` 分割线。 */
export interface DividerNode {
  readonly type: 'divider'
}

/** `[url]http://x[/url]` 或 `[url=http://x]文字[/url]`。children 为空时渲染 href。 */
export interface LinkNode {
  readonly type: 'link'
  readonly href: string
  readonly children: readonly BBCodeNode[]
}

/** `[uid]123[/uid]` / `[uid=123]名字[/uid]`。 */
export interface UserRefNode {
  readonly type: 'userRef'
  readonly uid: string
  readonly children: readonly BBCodeNode[]
}

/** `[tid]123[/tid]` / `[tid=123]标题[/tid]`。 */
export interface TopicRefNode {
  readonly type: 'topicRef'
  readonly tid: string
  readonly children: readonly BBCodeNode[]
}

/**
 * 指向某个楼层的 `[pid]`。`[pid=a,b,c]Reply[/pid]` 指向
 * `read.php?searchpost=1&pid=a`,所以 `pid` 取第一个参数;`args` 保留全部参数,
 * 回复链(ticket 26)要用到后面的 tid 与页码。
 */
export interface FloorRefNode {
  readonly type: 'floorRef'
  readonly pid: string
  readonly args: readonly string[]
  readonly children: readonly BBCodeNode[]
}

/** `[@用户名]`。 */
export interface MentionNode {
  readonly type: 'mention'
  readonly username: string
}

/**
 * 指向 NGA 附件空间的资源。
 *
 * `needsAttachBase` 为 true 表示 `src` 是相对路径(`./` 前缀已剥掉),渲染层要拼上
 * 附件域名——域名从 `read.php` 响应的 `__GLOBAL._ATTACH_BASE_VIEW` 动态获取,
 * 解析器不硬编码任何域名。缩略图后缀的剥离归渲染层(ticket 07)。
 */
export interface AttachmentRef {
  readonly src: string
  readonly needsAttachBase: boolean
}

/** `[img]` 与 `[noimg]`。`noimg` 的路径还要按发帖日期补 `mon_YYYYMM/DD/` 前缀。 */
export interface ImageNode extends AttachmentRef {
  readonly type: 'image'
  readonly variant: 'img' | 'noimg'
}

/** `[attach]`。 */
export interface AttachNode extends AttachmentRef {
  readonly type: 'attach'
}

/** `[album]`。内容原样保留,由渲染层决定相册展开方式。 */
export interface AlbumNode {
  readonly type: 'album'
  readonly value: string
}

/** `[flash]` / `[flash=video]` / `[flash=audio]`。ADR-0001 定为媒体卡片外跳,不内联播放。 */
export interface FlashNode extends AttachmentRef {
  readonly type: 'flash'
  readonly media: 'video' | 'audio' | 'flash'
}

/**
 * `[s:分类:名称]` 与默认套的 `[s:数字]`。
 *
 * `code` 是 `[s:` 与 `]` 之间的原文。怎么切分类与名称、查不到时怎么兜底,
 * 全归 `src/core/smilies` 的 `resolveSmiley(code)`——它照抄官方
 * `js_bbscode_core.js` 的切法,解析器不重复实现。
 */
export interface SmileyNode {
  readonly type: 'smiley'
  readonly code: string
}

/** `[dice]1d100[/dice]` 或 `[dice XdY]`。结果由 `src/core/local` 按 NGA 伪随机复算。 */
export interface DiceNode {
  readonly type: 'dice'
  readonly expression: string
}

/** `[lessernuke]`(版规警告块)、`[hip]`、`[item]`——统一成带标记的容器。 */
export interface BoxNode {
  readonly type: 'box'
  readonly variant: 'lessernuke' | 'hip' | 'item'
  /**
   * 只有 `lessernuke` 有:被处罚的是哪一种。标签写成 `[lessernuke2]` 这样带一位数字,
   * 官方 `ubbcode.lesserNuke` 按它换提示语(本帖发言 / 主题中被处罚 / 账号被锁定),
   * 不带数字等同 `post`。
   */
  readonly punishment?: 'post' | 'topic' | 'locked'
  readonly children: readonly BBCodeNode[]
}

export type BBCodeNode =
  | TextNode
  | LineBreakNode
  | StyleNode
  | ColorNode
  | SizeNode
  | FontNode
  | QuoteNode
  | CodeNode
  | CollapseNode
  | ListNode
  | TableNode
  | AlignNode
  | HeadingNode
  | DividerNode
  | LinkNode
  | UserRefNode
  | TopicRefNode
  | FloorRefNode
  | MentionNode
  | ImageNode
  | AttachNode
  | AlbumNode
  | FlashNode
  | SmileyNode
  | DiceNode
  | BoxNode
