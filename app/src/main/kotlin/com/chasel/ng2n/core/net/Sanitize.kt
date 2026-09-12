package com.chasel.ng2n.core.net

private const val JS_PREFIX = "window.script_muti_get_var_store="
private const val ERROR_TAIL = "/*error fill content"
private const val JS_COMMENT = "/*\$js\$*/"

private fun extractPayload(text: String): String {
  val at = text.indexOf(JS_PREFIX)
  if (at < 0) return text
  var rest = text.substring(at + JS_PREFIX.length)
  val scriptEnd = rest.indexOf("</script>")
  if (scriptEnd >= 0) rest = rest.substring(0, scriptEnd)
  return rest.split(JS_PREFIX).joinToString("")
}

private fun truncateErrorTail(text: String): String {
  val at = text.indexOf(ERROR_TAIL)
  return if (at > 0) text.substring(0, at) else text
}

private fun stripJsComment(text: String): String = text.split(JS_COMMENT).joinToString("")

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

private fun stripAssignmentWrapper(text: String): String {
  var result = text.jsTrim()
  while (result.endsWith(";")) result = result.substring(0, result.length - 1).jsTrimEnd()
  if (result.startsWith("(") && result.endsWith(")") && result.length >= 2) {
    result = result.substring(1, result.length - 1).jsTrim()
  }
  return result
}

fun sanitizeNgaJson(raw: String): String {
  var text = extractPayload(raw)
  text = truncateErrorTail(text)
  text = stripJsComment(text)
  text = fixIllegalNumbers(text)
  text = quoteIntegerKeysAndEscapeControls(text)
  return stripAssignmentWrapper(text)
}
