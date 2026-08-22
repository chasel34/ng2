package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.encoding.encodeUriComponent
import com.chasel.ng2n.core.net.encoding.gbkEncodeUriComponent

/**
 * 出站参数:逐参数选字符集。直译 `src/core/net/query.ts`。
 *
 * 需要用 GBK 而非 UTF-8 编码的参数值(API 文档 §0.5:参数编码不统一,必须逐接口对照,
 * 例如 `thread.php` 的 `key` 是 UTF-8 而同一接口的 `author` 是 GBK)。
 */
sealed interface QueryValue {
  /** 普通字符串,按 UTF-8 percent 编码。空串 = 删除该参数。 */
  @JvmInline value class Text(val value: String) : QueryValue

  /**
   * 数字。RN 版这里是 JS 的 `number`,但全仓库的 query 参数都是整数
   * (fid/tid/pid/page/uid/时间戳),所以收成 `Long`;真需要小数的场合传 [Text]。
   */
  @JvmInline value class Num(val value: Long) : QueryValue

  /** 布尔:`true` → `1`,`false` = 删除该参数(大量调用逻辑靠这条)。 */
  @JvmInline value class Flag(val value: Boolean) : QueryValue

  /** 标记按 GBK 编码。空串 = 删除该参数,且**不算** GBK 参数([hasGbkParam] 不计)。 */
  @JvmInline value class Gbk(val value: String) : QueryValue
}

/**
 * 参数表。**顺序有意义**(query 串按插入序拼),所以一律用保序的 map
 * ([queryOf] / `linkedMapOf`);值为 `null` = 这个参数被显式删掉(TS 的 `null`/`undefined`)。
 */
typealias QueryParams = Map<String, QueryValue?>

/** 标记某个参数值按 GBK 编码。 */
fun gbk(value: String): QueryValue.Gbk = QueryValue.Gbk(value)

/**
 * 保序地拼一张参数表。值可以是 `String` / `Int` / `Long` / `Boolean` / [QueryValue] / `null`。
 *
 * 重复键的行为与 JS 对象字面量一致:**后值覆盖,但位置留在第一次出现处**。
 */
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

/** 参数里有没有按 GBK 编码的值——决定要不要声明 `charset=GBK`、要不要撤掉 `__inchst=UTF8`。 */
fun hasGbkParam(params: QueryParams?): Boolean =
  params != null && params.values.any { it is QueryValue.Gbk && it.value != "" }

/**
 * 归一化成字符串;返回 null 表示这个参数应当被剔除。
 *
 * **空值参数必须从 query 中删除**(API 文档 §0.4):大量调用逻辑依赖它——
 * bool false 编码成空串即「不传」、fid/stid 二选一等。数字 `0` 要保留。
 */
private fun normalize(value: QueryValue?): String? = when (value) {
  null -> null
  is QueryValue.Flag -> if (value.value) "1" else null
  is QueryValue.Num -> value.value.toString()
  is QueryValue.Gbk -> if (value.value == "") null else gbkEncodeUriComponent(value.value)
  is QueryValue.Text -> if (value.value == "") null else encodeUriComponent(value.value)
}

/**
 * 拼 `key=value&…`(已剔除空值参数,值已按各自字符集编码);无参数时返回空串。
 * URL query 与 POST 表单体是同一套规则,两处共用。
 */
fun buildQueryString(params: QueryParams): String {
  val pairs = ArrayList<String>(params.size)
  for ((key, value) in params) {
    val encoded = normalize(value) ?: continue
    pairs.add("${encodeUriComponent(key)}=$encoded")
  }
  return pairs.joinToString("&")
}
