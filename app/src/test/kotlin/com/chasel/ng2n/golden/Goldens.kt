package com.chasel.ng2n.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从 classpath 读金样本。
 *
 * **不遍历目录**:资源打进 jar 后目录遍历不可靠(README 明写了),一律先读
 * `goldens/index.json` 拿到 `domains.<domain>` 的 case 名单,再逐文件读。
 * 索引里有、文件没有 = 报错,不是「跳过」。
 */
object Goldens {

  private val json = Json {
    // 金样本是生成物,格式不该有回旋余地:多一个键、少一个键都要炸出来
    ignoreUnknownKeys = false
    isLenient = false
  }

  private val index: JsonObject by lazy { json.parseToJsonElement(readText("goldens/index.json")).jsonObject }

  /** 索引里登记的全部 domain 名。 */
  fun domains(): List<String> = index.getValue("domains").jsonObject.keys.sorted()

  /** 索引里登记的总条数(README 的「当前规模」)。 */
  fun total(): Int = index.getValue("total").jsonPrimitive.content.toInt()

  fun caseNames(domain: String): List<String> {
    val domains = index.getValue("domains").jsonObject
    val names = domains[domain]
      ?: throw AssertionError(
        "goldens/index.json 里没有 domain `$domain`;有的是:${domains.keys.sorted()}",
      )
    return names.jsonArray.map { it.jsonPrimitive.content }
  }

  /** 一个 domain 的全部 case,顺序照索引。 */
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
          "它们在 native/app/src/test/resources/,由 RN 侧 `pnpm goldens:export` 生成。",
      )
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
  }
}
