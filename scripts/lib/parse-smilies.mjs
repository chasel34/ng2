/**
 * 从 NGA 官方前端脚本 js_bbscode_core.js 里提取 `ubbcode.smiles` 表情映射表。
 *
 * 该表是一段 JS 对象字面量,写法不规则(单/双引号混用、裸数字键、制表符对齐、
 * 行内 `//` 注释里还夹着引号),所以这里不 eval、也不用单条正则硬啃,而是先做一次
 * 尊重字符串字面量的扫描把注释剥掉,再逐层取 key/value。
 */

const TABLE_START = 'ubbcode.smiles'
/** 每个套系里标记套系中文名的伪 key。 */
const LABEL_KEY = '_______name'

/**
 * 剥掉 `//` 行注释与 `/* *\/` 块注释,但不碰字符串字面量内部的同名字符。
 * @param {string} source
 * @returns {string}
 */
function stripComments(source) {
  let out = ''
  let quote = null
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i]
    if (quote) {
      out += ch
      if (ch === '\\') {
        out += source[i + 1] ?? ''
        i += 1
      } else if (ch === quote) {
        quote = null
      }
      continue
    }
    if (ch === '"' || ch === "'") {
      quote = ch
      out += ch
      continue
    }
    if (ch === '/' && source[i + 1] === '/') {
      while (i < source.length && source[i] !== '\n') i += 1
      out += '\n'
      continue
    }
    if (ch === '/' && source[i + 1] === '*') {
      i += 2
      while (i < source.length && !(source[i] === '*' && source[i + 1] === '/')) i += 1
      i += 1
      continue
    }
    out += ch
  }
  return out
}

/**
 * 从 `text` 的 `openIndex`(必须指向 `{`)出发,返回配对 `}` 的下标。
 * @param {string} text
 * @param {number} openIndex
 * @returns {number}
 */
function findMatchingBrace(text, openIndex) {
  let depth = 0
  let quote = null
  for (let i = openIndex; i < text.length; i += 1) {
    const ch = text[i]
    if (quote) {
      if (ch === '\\') i += 1
      else if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'") {
      quote = ch
      continue
    }
    // 表里夹着 `//____display:'茶	ac	…'` 这类注释,其中的引号会把配对扫描带偏。
    if (ch === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i += 1
      continue
    }
    if (ch === '/' && text[i + 1] === '*') {
      i += 2
      while (i < text.length && !(text[i] === '*' && text[i + 1] === '/')) i += 1
      i += 1
      continue
    }
    if (ch === '{') depth += 1
    else if (ch === '}') {
      depth -= 1
      if (depth === 0) return i
    }
  }
  throw new Error('js_bbscode_core.js 里的 ubbcode.smiles 花括号没有闭合')
}

/** 匹配 `key : 'value'`,key 可裸可带引号。 */
const ENTRY_RE = /(?:'([^']*)'|"([^"]*)"|([A-Za-z0-9_$]+))\s*:\s*(?:'([^']*)'|"([^"]*)")/g
/** 匹配 `key : {`,即一个套系的开头。 */
const CATEGORY_RE = /(?:'([^']*)'|"([^"]*)"|([A-Za-z0-9_$]+))\s*:\s*\{/g

/**
 * @typedef {{ key: string, label: string, entries: Array<{ name: string, file: string }> }} SmileyCategory
 */

/**
 * @param {string} source js_bbscode_core.js 的完整源码(已解码为 UTF-16 字符串)
 * @returns {SmileyCategory[]} 按官方表里的顺序排列
 */
export function parseSmiliesTable(source) {
  const anchor = source.indexOf(TABLE_START)
  if (anchor < 0) throw new Error(`js_bbscode_core.js 里找不到 ${TABLE_START}`)
  const open = source.indexOf('{', anchor)
  if (open < 0) throw new Error(`${TABLE_START} 后面没有对象字面量`)
  // 只在表这一段里剥注释——整份脚本里的正则字面量含裸引号,全局剥会把引号状态带偏。
  const body = stripComments(source.slice(open, findMatchingBrace(source, open) + 1))

  /** @type {SmileyCategory[]} */
  const categories = []
  // 从 1 开始,`body[0]` 是整张表自己的 `{`。
  CATEGORY_RE.lastIndex = 1
  let match
  while ((match = CATEGORY_RE.exec(body)) !== null) {
    const key = match[1] ?? match[2] ?? match[3]
    const braceAt = body.indexOf('{', match.index)
    const end = findMatchingBrace(body, braceAt)
    const inner = body.slice(braceAt + 1, end)

    let label = ''
    /** @type {Array<{ name: string, file: string }>} */
    const entries = []
    ENTRY_RE.lastIndex = 0
    let entry
    while ((entry = ENTRY_RE.exec(inner)) !== null) {
      const name = entry[1] ?? entry[2] ?? entry[3]
      const value = entry[4] ?? entry[5]
      if (name === LABEL_KEY) label = value
      else entries.push({ name, file: value })
    }
    categories.push({ key, label, entries })
    CATEGORY_RE.lastIndex = end
  }

  if (categories.length === 0) throw new Error('ubbcode.smiles 解析出 0 个套系,格式可能变了')
  return categories
}
