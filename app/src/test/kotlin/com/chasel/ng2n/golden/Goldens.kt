package com.chasel.ng2n.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Goldens {

  private val json = Json {
    ignoreUnknownKeys = false
    isLenient = false
  }

  private val index: JsonObject by lazy { json.parseToJsonElement(readText("goldens/index.json")).jsonObject }

  fun domains(): List<String> = index.getValue("domains").jsonObject.keys.sorted()

  fun total(): Int = index.getValue("total").jsonPrimitive.content.toInt()

  fun caseNames(domain: String): List<String> {
    val domains = index.getValue("domains").jsonObject
    val names = domains[domain]
      ?: throw AssertionError(
        "goldens/index.json 里没有 domain `$domain`;有的是:${domains.keys.sorted()}",
      )
    return names.jsonArray.map { it.jsonPrimitive.content }
  }

  fun load(domain: String): List<GoldenCase> {
    val cases = caseNames(domain).map { name ->
      val path = "goldens/$domain/$name.json"
      GoldenCase.from(domain, json.parseToJsonElement(readText(path)))
    }
    check(cases.isNotEmpty()) { "domain `$domain` 一条 case 都没有" }
    cases.forEach { case ->
      check(case.name.isNotEmpty()) { "${case.resourcePath}: name 为空" }
    }
    val duplicates = cases.groupBy { it.name }.filterValues { it.size > 1 }.keys
    check(duplicates.isEmpty()) { "domain `$domain` 有重名 case:$duplicates" }
    return cases
  }

  private fun readText(path: String): String {
    val loader = Goldens::class.java.classLoader
      ?: throw AssertionError("拿不到 classloader")
    val stream = loader.getResourceAsStream(path)
      ?: throw AssertionError(
        "classpath 上找不到 $path —— 金样本没进测试资源?" +
          "请检查 app/src/test/resources/goldens/ 及其中的 index.json。",
      )
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
  }
}
