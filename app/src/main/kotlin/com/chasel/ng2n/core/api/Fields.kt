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

fun orderedValues(value: JsonElement?): List<JsonElement> = orderedEntries(value).map { it.second }

fun str(record: JsonObject, key: String): String? {
  val value = record[key]
  if (value !is JsonPrimitive || !value.isString) return null
  val trimmed = value.content.jsTrim()
  return trimmed.ifEmpty { null }
}

fun text(record: JsonObject, key: String): String? = str(record, key)?.let(::unescapeNgaText)

fun int(record: JsonObject, key: String): Long? {
  val value = record[key] ?: return null
  if (value !is JsonPrimitive) return null
  if (value.isString) {
    val parsed = jsNumber(value.content.jsTrim())
    return if (parsed.isFinite()) jsTrunc(parsed) else null
  }
  val number = value.content.toDoubleOrNull() ?: return null
  return if (number.isFinite()) jsTrunc(number) else null
}

fun nonZero(value: Long?): Long? = if (value == null || value == 0L) null else value
