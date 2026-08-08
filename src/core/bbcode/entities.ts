/**
 * NGA 文本的转义与反转义(读、写各一个方向)。
 *
 * NGA 会对正文做**双重** HTML 转义,并把所有码点 > 0xFFFF 的字符(emoji 等)
 * 拆成两个 UTF-16 码元的十进制实体。所以读取时要跑两轮实体解码,例:
 * `&amp;#55357;&amp;#56836;` → `&#55357;&#56836;` → `😄`。
 *
 * 写回去(签名、正文)要反着来一次:见 `escapeForSubmit`。
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

/**
 * 提交侧转义(API 文档 §13 第 4 条):不转的话旧接口会拒收或存成乱码。
 *
 * 要转成 UTF-16 码元十进制实体的是这几档:
 *
 * | 范围 | 说明 |
 * |---|---|
 * | 码点 > `0xFFFF` | emoji 等星平面字符,拆成代理对**两个**实体 |
 * | `0x200D` | 零宽连接符(家庭 emoji 这类 ZWJ 序列靠它连起来) |
 * | `0x2600`–`0x27BF` | 杂项符号与装饰符(`❤` 在这一档) |
 * | `0xFE00`–`0xFE0F` | 变体选择符(`❤️` 后面那个强制 emoji 呈现的码元) |
 *
 * 其余字符(含中文)原样留着——参数编码由 core/net 那层按接口定夺。
 * `unescapeNgaText` 是它的逆向,一转一解回得来原文。
 */
export function escapeForSubmit(text: string): string {
  let escaped = ''
  // for…of 按**码点**迭代,星平面字符一次拿到完整的一对代理码元
  for (const char of text) {
    const code = char.codePointAt(0) ?? 0
    if (code > 0xffff) {
      escaped += `&#${char.charCodeAt(0)};&#${char.charCodeAt(1)};`
    } else if (needsSubmitEscape(code)) {
      escaped += `&#${code};`
    } else {
      escaped += char
    }
  }
  return escaped
}

function needsSubmitEscape(code: number): boolean {
  return (
    code === 0x200d || (code >= 0x2600 && code <= 0x27bf) || (code >= 0xfe00 && code <= 0xfe0f)
  )
}
