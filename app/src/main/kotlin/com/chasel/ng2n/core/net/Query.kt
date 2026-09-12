package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.encoding.encodeUriComponent
import com.chasel.ng2n.core.net.encoding.gbkEncodeUriComponent

sealed interface QueryValue {
  @JvmInline value class Text(val value: String) : QueryValue

  @JvmInline value class Num(val value: Long) : QueryValue

  @JvmInline value class Flag(val value: Boolean) : QueryValue

  @JvmInline value class Gbk(val value: String) : QueryValue
}

typealias QueryParams = Map<String, QueryValue?>

fun gbk(value: String): QueryValue.Gbk = QueryValue.Gbk(value)

fun queryOf(vararg pairs: Pair<String, Any?>): QueryParams {
  val out = LinkedHashMap<String, QueryValue?>(pairs.size * 2)
  for ((key, value) in pairs) out[key] = toQueryValue(key, value)
  return out
}

private fun toQueryValue(key: String, value: Any?): QueryValue? = when (value) {
  null -> null
  is QueryValue -> value
  is String -> QueryValue.Text(value)
  is Int -> QueryValue.Num(value.toLong())
  is Long -> QueryValue.Num(value)
  is Boolean -> QueryValue.Flag(value)
  else -> error("参数 `$key` 的类型 ${value::class.simpleName} 不能进 query;显式包成 QueryValue")
}

fun hasGbkParam(params: QueryParams?): Boolean =
  params != null && params.values.any { it is QueryValue.Gbk && it.value != "" }

private fun normalize(value: QueryValue?): String? = when (value) {
  null -> null
  is QueryValue.Flag -> if (value.value) "1" else null
  is QueryValue.Num -> value.value.toString()
  is QueryValue.Gbk -> if (value.value == "") null else gbkEncodeUriComponent(value.value)
  is QueryValue.Text -> if (value.value == "") null else encodeUriComponent(value.value)
}

fun buildQueryString(params: QueryParams): String {
  val pairs = ArrayList<String>(params.size)
  for ((key, value) in params) {
    val encoded = normalize(value) ?: continue
    pairs.add("${encodeUriComponent(key)}=$encoded")
  }
  return pairs.joinToString("&")
}
