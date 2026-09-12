package com.chasel.ng2n.golden

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * Kotlin 结果 → `JsonElement`,好跟 `expected` 深度比较。
 *
 * 认得的类型故意只有这几种:再往上就该由被测那一层自己
 * `Json.encodeToJsonElement(value)`(`@Serializable` 数据类)后交进来,
 * 免得这里悄悄替谁做了一次「宽容转换」。
 */
fun toGoldenJson(value: Any?): JsonElement = when (value) {
  null -> JsonNull
  is JsonElement -> value
  is String -> JsonPrimitive(value)
  is Boolean -> JsonPrimitive(value)
  is Int, is Long, is Short, is Byte -> JsonPrimitive(value as Number)
  is Double -> {
    require(value.isFinite()) { "金样本里不允许非有限数字:$value" }
    JsonPrimitive(value)
  }
  is Float -> {
    require(value.isFinite()) { "金样本里不允许非有限数字:$value" }
    JsonPrimitive(value)
  }
  is BigDecimal -> JsonPrimitive(value)
  is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toGoldenJson(v) })
  is Iterable<*> -> JsonArray(value.map(::toGoldenJson))
  is Array<*> -> JsonArray(value.map(::toGoldenJson))
  else -> error("金样本对拍不认识的返回类型 ${value::class.qualifiedName};请自己转成 JsonElement")
}

/**
 * 抛错 → `expected.throws` 那个对象。
 *
 * 默认只认「不是 NgaError 的普通异常」那一档(README:`{ kind: "error", message }`)。
 * 票 04 落地 `NgaError` 后,在自己的 domain 测试里换一个描述器进来,补齐
 * `kind`/`retryable`/`code`/`status`/`via`——`retryable` 决定反封锁链走不走下去,
 * **是语义的一部分,漏掉等于没对拍**(ADR-0002)。
 */
fun interface GoldenThrowDescriber {
  fun describe(error: Throwable): JsonObject
}

val DefaultGoldenThrowDescriber = GoldenThrowDescriber { error ->
  buildJsonObject {
    put("kind", "error")
    put("message", error.message ?: error.toString())
  }
}

/** 一条用例的对拍结果:`null` = 过。 */
fun checkGolden(
  case: GoldenCase,
  describeThrow: GoldenThrowDescriber = DefaultGoldenThrowDescriber,
  compute: (GoldenCase) -> Any?,
): String? {
  val actual: JsonElement = try {
    toGoldenJson(compute(case))
  } catch (harness: AssertionError) {
    // 取 input 取错了 / 断言写错了 —— 这不是「被测函数抛错」,别混成 throws 期望
    return "对拍框架自己出错:${harness.message}"
  } catch (error: Throwable) {
    buildJsonObject { put("throws", describeThrow.describe(error)) }
  }

  val expected = case.expected
  if (case.expectsThrow && !(actual is JsonObject && actual.containsKey("throws"))) {
    return "期望抛错 ${expected.jsonThrows()},实际正常返回 ${actual.preview()}"
  }
  if (!case.expectsThrow && actual is JsonObject && actual.containsKey("throws") && actual.size == 1) {
    return "期望正常返回 ${expected.preview()},实际抛了 ${actual.jsonThrows()}"
  }

  val diffs = JsonDeepCompare.diff(expected, actual)
  if (diffs.isEmpty()) return null
  return buildString {
    append("${diffs.size} 处差异")
    diffs.take(MAX_REPORTED_DIFFS).forEach { diff ->
      append("\n    ${diff.path}")
      append("\n      期望 ${diff.expected}")
      append("\n      实际 ${diff.actual}")
      if (!diff.detail.isNullOrEmpty()) append("\n      ${diff.detail}")
    }
    if (diffs.size > MAX_REPORTED_DIFFS) {
      append("\n    …另有 ${diffs.size - MAX_REPORTED_DIFFS} 处未列出")
    }
  }
}

private const val MAX_REPORTED_DIFFS = 200

/** 单条用例断言。整 domain 跑用 [runGoldenDomain],它不会首错即停。 */
fun assertGolden(
  case: GoldenCase,
  describeThrow: GoldenThrowDescriber = DefaultGoldenThrowDescriber,
  compute: (GoldenCase) -> Any?,
) {
  val failure = checkGolden(case, describeThrow, compute) ?: return
  throw AssertionError("golden ${case.resourcePath}(fn=${case.fn})不匹配:$failure")
}

private fun JsonElement.jsonThrows(): String =
  ((this as? JsonObject)?.get("throws") as? JsonObject)?.preview(400) ?: preview()

// ---------------------------------------------------------------------------
// 表驱动跑法
// ---------------------------------------------------------------------------

/**
 * 一个 domain 的全量对拍。
 *
 * - 按 `fn` 分派:每个 fn 注册一个实现,索引里出现了没注册的 fn ⇒ **失败**(不是跳过)。
 * - 注册了却一条 case 都没命中 ⇒ 也失败(fn 名打错了)。
 * - 逐条跑完再汇总,失败用例名逐条列出;**不许首错即停**。
 */
fun runGoldenDomain(domain: String, configure: GoldenDomainSpec.() -> Unit) {
  val spec = GoldenDomainSpec(domain).apply(configure)
  spec.run()
}

class GoldenDomainSpec internal constructor(private val domain: String) {
  private val handlers = LinkedHashMap<String, (GoldenCase) -> Any?>()
  private var describeThrow: GoldenThrowDescriber = DefaultGoldenThrowDescriber

  /** 注册一个 TS 函数名对应的 Kotlin 实现。`body` 直接抛出的异常按「期望抛错」处理。 */
  fun fn(name: String, body: (GoldenCase) -> Any?) {
    require(handlers.put(name, body) == null) { "fn `$name` 注册了两次" }
  }

  fun throwsDescribedBy(describer: GoldenThrowDescriber) {
    describeThrow = describer
  }

  internal fun run() {
    val cases = Goldens.load(domain)
    val unhandled = cases.map { it.fn }.distinct().filter { it !in handlers }
    val unused = handlers.keys.filter { name -> cases.none { it.fn == name } }

    val failures = mutableListOf<String>()
    var passed = 0
    for (case in cases) {
      val handler = handlers[case.fn]
      if (handler == null) {
        failures += "  ✗ ${case.name}(fn=${case.fn}):没有注册实现"
        continue
      }
      val failure = checkGolden(case, describeThrow, handler)
      if (failure == null) {
        passed++
      } else {
        val note = case.note?.let { "\n    note: $it" } ?: ""
        failures += "  ✗ ${case.name}(fn=${case.fn})$note\n    $failure"
      }
    }

    val structural = buildList {
      if (unhandled.isNotEmpty()) add("domain `$domain` 里这些 fn 没有注册实现:$unhandled")
      if (unused.isNotEmpty()) add("注册了但一条 case 都没命中的 fn(名字打错了?):$unused")
    }

    if (failures.isEmpty() && structural.isEmpty()) return
    throw AssertionError(
      buildString {
        append("金样本 domain `$domain`:${cases.size} 条中 $passed 条通过,${failures.size} 条不匹配")
        structural.forEach { append("\n  ! $it") }
        failures.forEach { append("\n").append(it) }
      },
    )
  }
}
