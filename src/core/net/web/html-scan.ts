/**
 * 从一页 HTML 里抠东西的通用扫描器（Web 反解档专用，ADR-0002）。
 *
 * 不引 DOM 也不引 HTML 解析库：core 层零依赖，而反解只需要「按 id 取一段 innerHTML」
 * 与「把一串 JS 实参切开」两件事。写成带字符串状态的扫描而不是正则，是因为
 * 要抠的正是**用户内容**——正文里有 `'`、`"`、`{`、`)`、`</span>` 全是常事，
 * 裸正则会在别人贴一段代码时把整页反解带偏。
 */

/** 一段 JS 实参：数字/字符串/null，或 `$('id')` 这种拿不到值的表达式。 */
export type JsArgument =
  | { readonly kind: 'string'; readonly value: string }
  | { readonly kind: 'number'; readonly value: number }
  | { readonly kind: 'null' }
  /** 其它表达式，原文照留（`$('postcontent0')` 靠它取到里面那个 id） */
  | { readonly kind: 'expression'; readonly text: string }

/**
 * 从 `from` 起找 `open`/`close` 配对的那一段，返回**不含**两端括号的内容。
 * 括号计数时跳过字符串字面量（单双引号都算）与其中的转义。找不到配对返回 undefined。
 */
export function balancedSlice(
  text: string,
  from: number,
  open: string,
  close: string,
): { readonly body: string; readonly end: number } | undefined {
  const start = text.indexOf(open, from)
  if (start < 0) return undefined

  let depth = 0
  let quote: string | undefined
  for (let i = start; i < text.length; i += 1) {
    const char = text[i]!
    if (quote !== undefined) {
      if (char === '\\') i += 1
      else if (char === quote) quote = undefined
      continue
    }
    if (char === "'" || char === '"') {
      quote = char
      continue
    }
    if (char === open) depth += 1
    else if (char === close) {
      depth -= 1
      if (depth === 0) return { body: text.slice(start + 1, i), end: i + 1 }
    }
  }
  return undefined
}

/** 按顶层逗号切开实参串（字符串里的、嵌套括号里的逗号不算）。 */
function splitTopLevel(body: string): string[] {
  const parts: string[] = []
  let depth = 0
  let quote: string | undefined
  let start = 0
  for (let i = 0; i < body.length; i += 1) {
    const char = body[i]!
    if (quote !== undefined) {
      if (char === '\\') i += 1
      else if (char === quote) quote = undefined
      continue
    }
    if (char === "'" || char === '"') quote = char
    else if (char === '(' || char === '[' || char === '{') depth += 1
    else if (char === ')' || char === ']' || char === '}') depth -= 1
    else if (char === ',' && depth === 0) {
      parts.push(body.slice(start, i))
      start = i + 1
    }
  }
  parts.push(body.slice(start))
  return parts
}

/** JS 字符串字面量 → 值。只还原 `\'` `\"` `\\` `\n` `\t` `\r`，NGA 的实参里只有这些。 */
function unquote(literal: string): string {
  const quote = literal[0]!
  const inner = literal.slice(1, -1)
  let out = ''
  for (let i = 0; i < inner.length; i += 1) {
    const char = inner[i]!
    if (char !== '\\') {
      out += char
      continue
    }
    i += 1
    const next = inner[i]
    if (next === undefined) break
    out += next === 'n' ? '\n' : next === 't' ? '\t' : next === 'r' ? '\r' : next
  }
  return quote === '"' || quote === "'" ? out : literal
}

function parseArgument(raw: string): JsArgument {
  const text = raw.trim()
  if (text === 'null' || text === 'undefined' || text === '') return { kind: 'null' }
  if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith('"') && text.endsWith('"'))) {
    return { kind: 'string', value: unquote(text) }
  }
  if (/^[+-]?\d+(\.\d+)?$/.test(text)) return { kind: 'number', value: Number(text) }
  return { kind: 'expression', text }
}

/**
 * 找一处 `<callee>( … )` 调用并切开它的实参。`callee` 带上结尾那个 `(`
 * （`commonui.postArg.proc(`），免得匹配到同前缀的别的函数。
 * `from` 之后第一处；返回 `end` 便于连续找下一处。
 */
export function findCall(
  html: string,
  callee: string,
  from = 0,
): { readonly at: number; readonly args: readonly JsArgument[]; readonly end: number } | undefined {
  const at = html.indexOf(callee, from)
  if (at < 0) return undefined
  // 从 callee 自带的那个 `(` 上开始配对，而不是它后面——否则会跳到实参里的括号去
  const slice = balancedSlice(html, at + callee.length - 1, '(', ')')
  if (slice === undefined) return undefined
  return { at, args: splitTopLevel(slice.body).map(parseArgument), end: slice.end }
}

/** 同 `findCall`，把这一页里所有调用都找出来（按出现顺序）。 */
export function findCalls(
  html: string,
  callee: string,
): readonly { readonly at: number; readonly args: readonly JsArgument[] }[] {
  const calls: { at: number; args: readonly JsArgument[] }[] = []
  let cursor = 0
  for (;;) {
    const call = findCall(html, callee, cursor)
    if (call === undefined) return calls
    calls.push({ at: call.at, args: call.args })
    cursor = call.end
  }
}

/** `$('postcontent0')` → `postcontent0`；不是这个形状（含 `null`）返回 undefined。 */
export function elementIdOf(argument: JsArgument | undefined): string | undefined {
  if (argument === undefined || argument.kind !== 'expression') return undefined
  return /^\$\(\s*['"]([^'"]+)['"]\s*\)$/.exec(argument.text)?.[1]
}

/** 找 `id='<id>'` 所在标签的起始 `<` 位置。 */
function findTagWithId(html: string, id: string): number | undefined {
  // 现建一个：`g` 正则带 lastIndex 状态，共用一个实例会在嵌套调用里互相踩
  const attribute = /\sid\s*=\s*(['"])([^'"]*)\1/g
  for (;;) {
    const match = attribute.exec(html)
    if (match === null) return undefined
    if (match[2] !== id) continue
    const open = html.lastIndexOf('<', match.index)
    if (open >= 0) return open
  }
}

/**
 * 取 `id` 那个元素的 innerHTML（原样，不做实体解码）。
 *
 * **不解码是对的**：NGA 网页版里 `postcontent` 的 innerHTML 与 JSON 接口的
 * `content` 字段逐字节相同——`&amp;` 与 `<br/>` 两边都留着，下游 BBCode 解析器
 * 本来就按这个口径吃。多解一轮反而会把正文里的 `&amp;lt;` 解坏。
 */
export function innerHtmlOf(html: string, id: string): string | undefined {
  const open = findTagWithId(html, id)
  if (open === undefined) return undefined
  const tagName = /^<([a-zA-Z][\w-]*)/.exec(html.slice(open, open + 32))?.[1]
  if (tagName === undefined) return undefined

  const contentStart = html.indexOf('>', open)
  if (contentStart < 0) return undefined
  if (html[contentStart - 1] === '/') return ''

  // 同名标签可以嵌套（正文外面那层 span 里还可能有 span），按深度找收尾的那个
  const lower = html.toLowerCase()
  const openTag = `<${tagName.toLowerCase()}`
  const closeTag = `</${tagName.toLowerCase()}`
  let depth = 1
  let cursor = contentStart + 1
  while (depth > 0) {
    const nextOpen = lower.indexOf(openTag, cursor)
    const nextClose = lower.indexOf(closeTag, cursor)
    if (nextClose < 0) return undefined
    if (nextOpen >= 0 && nextOpen < nextClose) {
      const tagEnd = html.indexOf('>', nextOpen)
      if (tagEnd < 0) return undefined
      // `<br/>` 这种自闭合的不增加深度
      if (html[tagEnd - 1] !== '/') depth += 1
      cursor = tagEnd + 1
      continue
    }
    depth -= 1
    if (depth === 0) return html.slice(contentStart + 1, nextClose)
    cursor = nextClose + closeTag.length
  }
  return undefined
}

/** 取 `var x = '…'` / `x=parseInt('…')` 里那个整数。 */
export function readIntVariable(html: string, name: string): number | undefined {
  const pattern = new RegExp(`${name}\\s*=\\s*(?:parseInt\\(\\s*)?'?(-?\\d+)'?`)
  const raw = pattern.exec(html)?.[1]
  return raw === undefined ? undefined : Number(raw)
}

/** 取 `x = 'value'` 里那个字符串。 */
export function readStringVariable(html: string, name: string): string | undefined {
  const pattern = new RegExp(`${name}\\s*=\\s*'((?:[^'\\\\]|\\\\.)*)'`)
  const raw = pattern.exec(html)?.[1]
  return raw === undefined ? undefined : unquote(`'${raw}'`)
}

/** 取 `<!--<name>start-->…<!--<name>end-->` 之间的内容。 */
export function readMarkedSection(html: string, name: string): string | undefined {
  const open = `<!--${name}start-->`
  const close = `<!--${name}end-->`
  const from = html.indexOf(open)
  if (from < 0) return undefined
  const to = html.indexOf(close, from + open.length)
  return to < 0 ? undefined : html.slice(from + open.length, to)
}

/**
 * 解一串 JS 对象字面量（`[{aid:'',url:'…'},…]`）里每个对象的 `key:'value'` 对。
 * 键不带引号，所以不能走 JSON.parse；值一律当字符串收（下游 `int()` 会再转）。
 */
export function parseObjectLiterals(source: string): readonly Record<string, string>[] {
  const objects: Record<string, string>[] = []
  let cursor = 0
  for (;;) {
    const slice = balancedSlice(source, cursor, '{', '}')
    if (slice === undefined) return objects
    const fields: Record<string, string> = {}
    const pattern = /([A-Za-z_]\w*)\s*:\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"|(-?\d+))/g
    for (;;) {
      const match = pattern.exec(slice.body)
      if (match === null) break
      fields[match[1]!] = unquote(`'${match[2] ?? match[3] ?? match[4] ?? ''}'`)
    }
    objects.push(fields)
    cursor = slice.end
  }
}
