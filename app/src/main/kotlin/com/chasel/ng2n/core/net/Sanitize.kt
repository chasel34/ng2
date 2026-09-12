package com.chasel.ng2n.core.net

/**
 * NGA 返回的「JSON」不合法,解析之前必须清洗(API 文档 §0.6)。直译 `src/core/net/sanitize.ts`。
 * 步骤取自 Android `ArticleConvertFactory` 的 8 步与 MNGA 的 2 步的并集,**顺序敏感**。
 *
 * **唯一一步没照做的是第 5 步「删坏字段 `"alterinfo":"[xxx] "`」**(2026-08-08,RN 侧 07 票)。
 * 实测这个字段的真身是 `"[E<时间戳> <编辑人 uid> <编辑人名>]<TAB>"`,抓包里每一条都长这样
 * ——所谓「坏」只是结尾那个**裸 TAB** 让 `JSON.parse` 挂掉,而本文件第 7 步本来就把
 * 字符串内的裸控制字符转义了,轮不到它坏;上游没有第 7 步才只能整段删。何况上游那条特征
 * (方括号 + 结尾空白)的括号内容不允许出现引号,所以它**从来**命中不了真正解析不了的形态
 * (未转义引号)。留着等于净亏:把「本楼被编辑过」连同编辑人一起删掉,详情页就再也认不出
 * 编辑标记。**这条与上游参考实现相反,是刻意的,勿修**。
 */

private const val JS_PREFIX = "window.script_muti_get_var_store="
private const val ERROR_TAIL = "/*error fill content"
private const val JS_COMMENT = "/*\$js\$*/"

/**
 * 1. 剥 JS 变量赋值前缀(`noprefix` 参数不总生效,所以解析侧必须自己剥),
 * 顺带处理 `lite=htmljs`:那种响应是整页 HTML,JSON 夹在 `<script>` 里,
 * 从前缀位置切到首个 `</script>` 即可,前后的 HTML 都不要。
 */
private fun extractPayload(text: String): String {
  val at = text.indexOf(JS_PREFIX)
  if (at < 0) return text
  var rest = text.substring(at + JS_PREFIX.length)
  val scriptEnd = rest.indexOf("</script>")
  if (scriptEnd >= 0) rest = rest.substring(0, scriptEnd)
  // 一次响应里可能出现多段赋值,其余前缀按 Android 的做法直接抹掉
  return rest.split(JS_PREFIX).joinToString("")
}

/**
 * 2. 截断错误尾巴。
 *
 * 判据是 `> 0` 而不是 `>= 0`:整条响应就是这段垃圾(下标 0)时截出来是空串,
 * 那还不如把原文留给信封层报「不是合法 JSON」,错误信息里能看见服务端到底回了什么。
 */
private fun truncateErrorTail(text: String): String {
  val at = text.indexOf(ERROR_TAIL)
  return if (at > 0) text.substring(0, at) else text
}

/** 3. 去注释标记。 */
private fun stripJsComment(text: String): String = text.split(JS_COMMENT).joinToString("")

/**
 * 4. 修非法数字:`"content":+123` / `"content":0123` 都不是合法 JSON,转成字符串。
 * subject / author 同理。
 *
 * 结尾用「后面不是数字」的前瞻而不是硬要求逗号,免得漏掉 `]`、`}` 或空白收尾的写法。
 */
private val ILLEGAL_NUMBER_FIELDS = listOf("content", "subject", "author")

private val LEADING_PLUS = ILLEGAL_NUMBER_FIELDS.associateWith { field ->
  Regex("\"$field\":\\+(\\d+)(?!\\d)")
}

private val LEADING_ZERO = ILLEGAL_NUMBER_FIELDS.associateWith { field ->
  Regex("\"$field\":(0\\d+)(?!\\d)")
}

private fun fixIllegalNumbers(text: String): String {
  var result = text
  for (field in ILLEGAL_NUMBER_FIELDS) {
    result = LEADING_PLUS.getValue(field).replace(result) { match ->
      "\"$field\":\"+${match.groupValues[1]}\""
    }
    result = LEADING_ZERO.getValue(field).replace(result) { match ->
      "\"$field\":\"${match.groupValues[1]}\""
    }
  }
  return result
}

private val CONTROL_ESCAPES: Map<Int, String> = mapOf(
  0x08 to "\\b",
  0x09 to "\\t",
  0x0a to "\\n",
  0x0c to "\\f",
  0x0d to "\\r",
)

/**
 * 6 + 7 合成一遍带字符串状态的扫描:
 * - 结构位置上给整数 key 加引号(`{,}` 后面紧跟数字再跟冒号)——NGA 会把整数直接当 key;
 * - 字符串内部的裸控制字符转义。
 *
 * 之所以不用 MNGA 那条裸正则 `([{,}]\s*)(\d+)(:)`,是因为它同样会命中正文字符串里的
 * `,123:`,把用户内容改坏后反而解析失败——那会被误判成被封而触发反封锁链。
 */
private fun quoteIntegerKeysAndEscapeControls(text: String): String {
  val out = StringBuilder(text.length)
  var inString = false
  var escaped = false
  var i = 0

  while (i < text.length) {
    val char = text[i]
    val code = char.code

    if (inString) {
      when {
        escaped -> {
          out.append(char)
          escaped = false
        }
        char == '\\' -> {
          out.append(char)
          escaped = true
        }
        char == '"' -> {
          out.append(char)
          inString = false
        }
        code < 0x20 -> out.append(CONTROL_ESCAPES[code] ?: "\\u%04x".format(code))
        else -> out.append(char)
      }
      i += 1
      continue
    }

    if (char == '"') {
      out.append(char)
      inString = true
      i += 1
      continue
    }

    if (char == '{' || char == ',' || char == '}') {
      // 结构符后可能跟空白 + 整数 key + 冒号
      var j = i + 1
      while (j < text.length && isJsWhitespace(text[j])) j += 1
      var digitsEnd = j
      while (digitsEnd < text.length && text[digitsEnd] in '0'..'9') digitsEnd += 1
      if (digitsEnd > j && digitsEnd < text.length && text[digitsEnd] == ':') {
        out.append(text, i, j).append('"').append(text, j, digitsEnd).append('"')
        i = digitsEnd
        continue
      }
    }

    out.append(char)
    i += 1
  }

  return out.toString()
}

/**
 * 收尾:去掉 JS 赋值残留的外层括号与结尾分号。
 * §0.6 的清单里没有这一条,但 `lite=js` 实际会返回 `=({…});` 这种形态。
 */
private fun stripAssignmentWrapper(text: String): String {
  var result = text.jsTrim()
  while (result.endsWith(";")) result = result.substring(0, result.length - 1).jsTrimEnd()
  if (result.startsWith("(") && result.endsWith(")") && result.length >= 2) {
    result = result.substring(1, result.length - 1).jsTrim()
  }
  return result
}

/**
 * 把 NGA 的伪 JSON 洗成合法 JSON 文本。纯字符串变换,**不做解析**。
 */
fun sanitizeNgaJson(raw: String): String {
  var text = extractPayload(raw)
  text = truncateErrorTail(text)
  text = stripJsComment(text)
  text = fixIllegalNumbers(text)
  text = quoteIntegerKeysAndEscapeControls(text)
  return stripAssignmentWrapper(text)
}
