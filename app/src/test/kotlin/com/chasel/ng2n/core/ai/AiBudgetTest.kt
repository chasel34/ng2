package com.chasel.ng2n.core.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AiBudgetTest {
  private fun book() = AiBudgetBook(listOf(AiBudgetAnalysis("a", "one", 100), AiBudgetAnalysis("b", "two", 100)))
  private fun request(id: String, analysis: String = "a", day: String = "2026-09-14") =
    AiBudgetRequest(id, analysis, if (analysis == "a") "one" else "two", day, 60, AiPrice())

  @Test fun concurrentReservationsShareDailyLimit() = runTest {
    val lock = Mutex()
    var book = book()
    val accepted = listOf("a", "b").map { id -> async(Dispatchers.Default) {
      runCatching { lock.withLock { book = book.reserve(request(id, id), 100) } }.isSuccess
    } }.awaitAll()
    assertEquals(1, accepted.count { it })
    assertEquals(60L, book.requests.sumOf { it.charged })
  }

  @Test fun usageReplayIsIdempotentAndReasoningIsIncludedOnlyOnce() {
    val first = book().reserve(request("r"), null).settle("r", 100, 20, 50)
    assertEquals(first, first.settle("r", 999, 999))
    assertEquals(AiPrice().cost(100, 20, 50), first.requests.single().cost)
    assertEquals(20L, first.requests.single().output)
  }

  @Test fun restartRetainsPendingAndCrossDayReservations() {
    val active = book().reserve(request("r", day = "2026-09-13"), null)
    val restart = Json.decodeFromString<AiBudgetBook>(Json.encodeToString(active)).pending()
    assertEquals(60L, restart.requests.single().reserved)
    assertEquals("2026-09-13", restart.requests.single().day)
    assertEquals("pending_verification", restart.requests.single().status)
    assertTrue(runCatching { restart.reserve(request("next", "b"), 100) }.exceptionOrNull() is AiBudgetExceeded)
  }

  @Test fun moreAllowanceDoesNotBypassDailyLimitAndStopDisallowsRequests() {
    val active = book().reserve(request("r"), null)
    assertFalse((runCatching { active.reserve(request("next"), null) }.exceptionOrNull() as AiBudgetExceeded).daily)
    val more = active.copy(analyses = active.analyses.map { it.copy(limit = 200) })
    assertEquals(2, more.reserve(request("next"), null).requests.size)
    assertTrue((runCatching { more.reserve(request("next"), 100) }.exceptionOrNull() as AiBudgetExceeded).daily)
    val stopped = more.copy(analyses = more.analyses.map { it.copy(stopped = true) })
    assertTrue(runCatching { stopped.reserve(request("next"), null) }.exceptionOrNull() is AiBudgetExceeded)
  }

  @Test fun legacyUsdBooksConvertOnceIntoCny() {
    // 旧账本省略了默认价字段，只有谷时价带数值；金额按 20:3 换算，已结算请求按 token 重算。
    val payload = """{"analyses":[{"id":"a","conversation":"c","limit":50000}],"requests":[
      {"id":"r1","analysis":"a","conversation":"c","day":"2026-09-14","reserved":9000,"price":{},"status":"settled","input":1000,"output":100,"cost":420},
      {"id":"r2","analysis":"a","conversation":"c","day":"2026-09-14","reserved":3000,"price":{"hit":3,"miss":150,"output":600},"status":"pending_verification"}]}"""
    val converted = kotlinx.serialization.json.Json.decodeFromString<AiBudgetBook>(payload).convertLegacyUsd()
    assertEquals(AI_CURRENCY, converted.currency)
    assertEquals(333_334L, converted.analyses.single().limit)
    val settled = converted.requests.first()
    assertEquals(AiPrice().cost(1000, 100), settled.cost)
    assertEquals(60_000L, settled.reserved)
    val pending = converted.requests.last()
    assertEquals(AiPrice(hit = 20, miss = 1000, output = 4000), pending.price)
    assertEquals(20_000L, pending.reserved)
    assertEquals(null, pending.cost)
    assertEquals(converted, converted.convertLegacyUsd())
  }

  @Test fun peakPriceAndUncertainTimeAreConservative() {
    val peak = Instant.parse("2026-09-14T01:00:00Z").toEpochMilli()
    val valley = Instant.parse("2026-09-14T04:00:00Z").toEpochMilli()
    assertEquals(2000L, AiPrice.at(peak, true).miss)
    assertEquals(1000L, AiPrice.at(valley, true).miss)
    assertEquals(2000L, AiPrice.at(valley).miss)
    assertEquals("2026-09-14", aiDay(peak))
    assertEquals(8_040_000L, AiPrice().cost(1_000_000, 1_000_000, 1_000_000))
  }

  @Test fun failureCategoriesKeepUnknownRequestsOutOfAutomaticRetries() {
    mapOf(401 to AiFailure.AUTH, 402 to AiFailure.BALANCE, 400 to AiFailure.PARAMETERS,
      415 to AiFailure.PARAMETERS, 422 to AiFailure.PARAMETERS, 429 to AiFailure.RATE,
      500 to AiFailure.SERVICE, 503 to AiFailure.SERVICE).forEach { (status, category) ->
      assertEquals(category, classifyAiFailure(Exception("HTTP $status"), false))
      assertEquals(status in listOf(429, 500, 503), category.retryable)
      assertEquals(AiFailure.INTERRUPTED, classifyAiFailure(Exception("HTTP $status"), true))
    }
    assertFalse(classifyAiFailure(Exception("connection reset after send"), false).retryable)
    listOf(415, 404, 413).forEach { assertEquals(it, rejectedHttpStatus("模型 HTTP $it")) }
    assertNull(rejectedHttpStatus("模型 HTTP 429"))
    assertNull(rejectedHttpStatus("模型 HTTP 503"))
  }
}
