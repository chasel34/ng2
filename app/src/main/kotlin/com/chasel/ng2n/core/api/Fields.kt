package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.bbcode.unescapeNgaText
import com.chasel.ng2n.core.net.jsNumber
import com.chasel.ng2n.core.net.jsOwnEntries
import com.chasel.ng2n.core.net.jsTrim
import com.chasel.ng2n.core.net.jsTrunc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 手工遍历 NGA 响应的公共小工具。直译 `src/core/api/fields.ts`。
 *
 * NGA 的 `data` 里**大量用字符串数字键当数组**(API 文档 §0.6),字段随时可能缺、
 * 可能换类型,自动 JSON 映射全线失效。各接口的解析器都要靠这几个函数把
 * 「取一个字符串」「取一个整数」「按数组顺序遍历」写得短一点。
 */

/**
 * 按数字键升序取键值对;非数字键排在后面,保持原有顺序。
 *
 * **真数组也走这条路**:§0.6 说的「用字符串数字键当数组」只是 `__output=8` 的习惯,
 * `__output=11` 同一个 `__T` 下发的就是货真价实的 JSON 数组。TS 的 `isRecord` 按约定把
 * 数组排除在外,不单独认一下的话整页主题会静默变成 0 条——`errors.ts` 认 `error` 的数组
 * 形态时踩过同一个坑(2026-08-14,fid=414 打不开的排查;ADR-0002 第 10 条)。
 * 数组的下标正是 `0`、`1`… 这样的数字键,后面的排序逻辑原样适用。
 */
fun orderedEntries(value: JsonElement?): List<Pair<String, JsonElement>> {
  val entries: List<Pair<String, JsonElement>> = when (value) {
    is JsonArray -> value.mapIndexed { index, item -> index.toString() to item }
    is JsonObject -> jsOwnEntries(value)
    else -> return emptyList()
  }
  return entries
    .mapIndexed { index, entry -> Triple(entry, jsNumber(entry.first), index) }
    .sortedWith { a, b ->
      val aNum = a.second.isFinite()
      val bNum = b.second.isFinite()
      when {
        aNum && bNum -> a.second.compareTo(b.second)
        aNum != bNum -> if (aNum) -1 else 1
        else -> a.third - b.third
      }
    }
    .map { it.first }
}

/** 同 [orderedEntries],只要值。 */
fun orderedValues(value: JsonElement?): List<JsonElement> = orderedEntries(value).map { it.second }

/** 非空字符串字段(已 trim),否则 `null`。 */
fun str(record: JsonObject, key: String): String? {
  val value = record[key]
  if (value !is JsonPrimitive || !value.isString) return null
  val trimmed = value.content.jsTrim()
  return trimmed.ifEmpty { null }
}

/**
 * 会被 HTML 转义的文本字段(标题这类要直接上屏的)。
 *
 * 服务端不只对正文做转义,`subject` 也一样:实测搜索结果里是
 * `&lt;第六感&gt;那个小孩…`、精华区里是 `1周年&#39;魔力印度&#39;新版本上线`。
 * 正文走 BBCode 解析时已经反转义过,标题不经过那条路径,所以在这里补上——
 * 用的是同一个两轮解码(`core/bbcode/Entities.kt`),emoji 的代理对实体也一并还原。
 */
fun text(record: JsonObject, key: String): String? = str(record, key)?.let(::unescapeNgaText)

/**
 * 整数字段。服务端偶尔把数字写成字符串,一并收下。
 *
 * 返回 `Long` 而不是 `Int`:NGA 的 `credit`(权限位图)、`bit`、金钱字段都会越过 32 位,
 * TS 那边是 double 全都装得下,收成 `Int` 会静默截断。
 */
fun int(record: JsonObject, key: String): Long? {
  val value = record[key] ?: return null
  if (value !is JsonPrimitive) return null // 对象 / 数组
  if (value.isString) {
    val parsed = jsNumber(value.content.jsTrim())
    return if (parsed.isFinite()) jsTrunc(parsed) else null
  }
  // 不带引号的原语:JSON 里只可能是 number / true / false / null。
  // 后三者在 TS 侧 `typeof` 既不是 number 也不是 string ⇒ undefined。
  val number = value.content.toDoubleOrNull() ?: return null
  return if (number.isFinite()) jsTrunc(number) else null
}

/**
 * 把 0 当成「没这个值」。
 * NGA 分不清「字段缺省」与「填 0」——普通版块就常带一个 `stid:0`(=不是合集)。
 */
fun nonZero(value: Long?): Long? = if (value == null || value == 0L) null else value
