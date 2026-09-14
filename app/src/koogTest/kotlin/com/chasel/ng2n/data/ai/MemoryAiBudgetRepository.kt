package com.chasel.ng2n.data.ai

import com.chasel.ng2n.core.ai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class MemoryAiBudgetRepository : AiBudgetRepository {
  override val books = MutableStateFlow(AiBudgetBook())
  private val lock = Mutex()
  override suspend fun change(update: (AiBudgetBook) -> AiBudgetBook) { lock.withLock { books.value = update(books.value) } }
  override suspend fun initialize() = Unit
  override suspend fun allowance() = 50_000L
  override suspend fun begin(conversation: String, previous: String?): String {
    if (previous != null) return previous
    val id = UUID.randomUUID().toString()
    change { it.copy(analyses = it.analyses + AiBudgetAnalysis(id, conversation, allowanceValue)) }
    return id
  }
  var allowanceValue = 50_000L
  var limitsValue = AiRunLimits()
  override suspend fun limits(entry: String) = limitsValue
  override suspend fun decide(id: String, more: Boolean, amount: Long) = change { book -> book.copy(analyses = book.analyses.map {
    if (it.id == id) it.copy(limit = if (more) it.limit + amount else it.limit, stopped = !more) else it
  }) }
  override suspend fun reserve(analysis: String, input: Long, images: Int, retry: Boolean, output: Long?): String {
    val id = UUID.randomUUID().toString()
    change { book -> book.reserve(AiBudgetRequest(id, analysis, book.analyses.single { it.id == analysis }.conversation,
      "2026-09-14", AiPrice().cost(input, output ?: limitsValue.maxTokens.toLong()), AiPrice(), images = images, retry = retry), null) }
    return id
  }
}
