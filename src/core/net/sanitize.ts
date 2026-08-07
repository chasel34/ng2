/**
 * NGA 返回的「JSON」不合法，`JSON.parse` 之前必须清洗（API 文档 §0.6）。
 * 步骤取自 Android `ArticleConvertFactory` 的 8 步与 MNGA 的 2 步的并集。
 */

const JS_PREFIX = 'window.script_muti_get_var_store='
const ERROR_TAIL = '/*error fill content'

/**
 * 1. 剥 JS 变量赋值前缀（`noprefix` 参数不总生效，所以解析侧必须自己剥），
 * 顺带处理 `lite=htmljs`：那种响应是整页 HTML，JSON 夹在 `<script>` 里，
 * 从前缀位置切到首个 `</script>` 即可，前后的 HTML 都不要。
 */
function extractPayload(text: string): string {
  const at = text.indexOf(JS_PREFIX)
  if (at < 0) return text
  let rest = text.slice(at + JS_PREFIX.length)
  const scriptEnd = rest.indexOf('</script>')
  if (scriptEnd >= 0) rest = rest.slice(0, scriptEnd)
  // 一次响应里可能出现多段赋值，其余前缀按 Android 的做法直接抹掉
  return rest.split(JS_PREFIX).join('')
}

/** 2. 截断错误尾巴。 */
function truncateErrorTail(text: string): string {
  const at = text.indexOf(ERROR_TAIL)
  return at > 0 ? text.slice(0, at) : text
}

/** 3. 去注释标记。 */
function stripJsComment(text: string): string {
  return text.split('/*$js$*/').join('')
}

/**
 * 4. 修非法数字：`"content":+123` / `"content":0123` 都不是合法 JSON，转成字符串。
 * subject / author 同理。
 *
 * 结尾用「后面不是数字」的前瞻而不是硬要求逗号，免得漏掉 `]`、`}` 或空白收尾的写法。
 */
const ILLEGAL_NUMBER_FIELDS = ['content', 'subject', 'author'] as const

function fixIllegalNumbers(text: string): string {
  let result = text
  for (const field of ILLEGAL_NUMBER_FIELDS) {
    result = result
      .replace(new RegExp(`"${field}":\\+(\\d+)(?!\\d)`, 'g'), `"${field}":"+$1"`)
      .replace(new RegExp(`"${field}":(0\\d+)(?!\\d)`, 'g'), `"${field}":"$1"`)
  }
  return result
}

/**
 * 5. 删坏字段：`"alterinfo":"[xxx] "` 整段删（部分页面打不开的原因）。
 *
 * 比上游的 `[(\w|\s)+]` 略宽——Java 的 `\w` 不匹配中文，而实际编辑记录是中文的，
 * 上游那条对中文站点等于没生效。仍然保留「方括号 + 结尾空白」这个特征，
 * 免得把正常的 alterinfo 也删掉。
 */
function dropBrokenAlterinfo(text: string): string {
  return text.replace(/"alterinfo":"\[[^"\\\]]*\]\s+",/g, '')
}

const CONTROL_ESCAPES: Record<number, string> = {
  0x08: '\\b',
  0x09: '\\t',
  0x0a: '\\n',
  0x0c: '\\f',
  0x0d: '\\r',
}

/**
 * 6 + 7 合成一遍带字符串状态的扫描：
 * - 结构位置上给整数 key 加引号（`{,}` 后面紧跟数字再跟冒号）——NGA 会把整数直接当 key；
 * - 字符串内部的裸控制字符转义。
 *
 * 之所以不用 MNGA 那条裸正则 `([{,}]\s*)(\d+)(:)`，是因为它同样会命中正文字符串里的
 * `,123:`，把用户内容改坏后反而解析失败——那会被误判成被封而触发反封锁链。
 */
function quoteIntegerKeysAndEscapeControls(text: string): string {
  let out = ''
  let inString = false
  let escaped = false
  let i = 0

  while (i < text.length) {
    const char = text[i]!
    const code = text.charCodeAt(i)

    if (inString) {
      if (escaped) {
        out += char
        escaped = false
      } else if (char === '\\') {
        out += char
        escaped = true
      } else if (char === '"') {
        out += char
        inString = false
      } else if (code < 0x20) {
        out += CONTROL_ESCAPES[code] ?? `\\u${code.toString(16).padStart(4, '0')}`
      } else {
        out += char
      }
      i += 1
      continue
    }

    if (char === '"') {
      out += char
      inString = true
      i += 1
      continue
    }

    if (char === '{' || char === ',' || char === '}') {
      // 结构符后可能跟空白 + 整数 key + 冒号
      let j = i + 1
      while (j < text.length && /\s/.test(text[j]!)) j += 1
      let digitsEnd = j
      while (digitsEnd < text.length && text[digitsEnd]! >= '0' && text[digitsEnd]! <= '9') {
        digitsEnd += 1
      }
      if (digitsEnd > j && text[digitsEnd] === ':') {
        out += text.slice(i, j) + '"' + text.slice(j, digitsEnd) + '"'
        i = digitsEnd
        continue
      }
    }

    out += char
    i += 1
  }

  return out
}

/**
 * 收尾：去掉 JS 赋值残留的外层括号与结尾分号。
 * §0.6 的清单里没有这一条，但 `lite=js` 实际会返回 `=({…});` 这种形态。
 */
function stripAssignmentWrapper(text: string): string {
  let result = text.trim()
  while (result.endsWith(';')) result = result.slice(0, -1).trimEnd()
  if (result.startsWith('(') && result.endsWith(')')) {
    result = result.slice(1, -1).trim()
  }
  return result
}

/**
 * 把 NGA 的伪 JSON 洗成合法 JSON 文本。纯字符串变换，不做解析。
 */
export function sanitizeNgaJson(raw: string): string {
  let text = extractPayload(raw)
  text = truncateErrorTail(text)
  text = stripJsComment(text)
  text = fixIllegalNumbers(text)
  text = dropBrokenAlterinfo(text)
  text = quoteIntegerKeysAndEscapeControls(text)
  return stripAssignmentWrapper(text)
}
