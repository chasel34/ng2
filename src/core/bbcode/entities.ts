/**
 * NGA 读取侧文本反转义。
 *
 * NGA 会对正文做**双重** HTML 转义,并把所有码点 > 0xFFFF 的字符(emoji 等)
 * 拆成两个 UTF-16 码元的十进制实体。所以读取时要跑两轮实体解码,例:
 * `&amp;#55357;&amp;#56836;` → `&#55357;&#56836;` → `😄`。
 */

const NAMED_ENTITIES: Record<string, string> = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  nbsp: ' ',
}

export function unescapeNgaText(raw: string): string {
  return dropLoneSurrogates(decodeHtmlEntities(decodeHtmlEntities(raw)))
}

const LONE_SURROGATE = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g

function dropLoneSurrogates(input: string): string {
  return input.replace(LONE_SURROGATE, '�')
}

const ENTITY_PATTERN = /&(?:#(\d+)|#[xX]([0-9a-fA-F]+)|([a-zA-Z][a-zA-Z0-9]*));/g

function decodeHtmlEntities(input: string): string {
  return input.replace(
    ENTITY_PATTERN,
    (match, decimal?: string, hex?: string, name?: string) => {
      if (decimal !== undefined) return fromCodeUnit(Number.parseInt(decimal, 10))
      if (hex !== undefined) return fromCodeUnit(Number.parseInt(hex, 16))
      const replacement = NAMED_ENTITIES[name!]
      return replacement === undefined ? match : replacement
    },
  )
}

/**
 * NGA 把星平面字符拆成两个 UTF-16 码元实体,所以这里按**码元**而非码点还原:
 * 相邻的高低代理码元在 JS 字符串里拼接后天然组成一个星平面字符。
 */
function fromCodeUnit(code: number): string {
  if (!Number.isFinite(code) || code < 0 || code > 0x10ffff) return ''
  return code <= 0xffff ? String.fromCharCode(code) : String.fromCodePoint(code)
}
