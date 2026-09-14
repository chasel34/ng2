package com.chasel.ng2n.data.ai

import androidx.room.*
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.data.db.Ng2nDatabase
import com.chasel.ng2n.data.ai.settings.AiSettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Entity(tableName = "ai_budget")
data class AiBudgetEntity(@PrimaryKey val id: Int = 1, val payload: String)
@Dao
interface AiBudgetDao {
  @Query("SELECT payload FROM ai_budget WHERE id = 1") suspend fun read(): String?
  @Query("SELECT payload FROM ai_budget WHERE id = 1") fun observe(): Flow<String?>
  @Upsert suspend fun put(value: AiBudgetEntity)
}

// 档位越低，输出上限与允许的工具次数越小，让预留贴近该档真实能做完的工作量。
// 个人入口的首份报告是一整块 JSON：最多 300 条样本的 processedSourceIds 加上各卡正文与时间线，
// 按约 12000 字估算需要 8192 token 正文预算，思考另计 4096，短答案档位的上限装不下。
fun runLimitsFor(allowance: String, entry: String = "主题"): AiRunLimits {
  val limits = when (allowance) {
    "short" -> AiRunLimits(iterations = 24, toolCalls = 12, outputTokens = 768, thinkingTokens = 768)
    "long" -> AiRunLimits(iterations = 48, toolCalls = 32)
    "higher" -> AiRunLimits(iterations = 64, toolCalls = 48)
    else -> AiRunLimits()
  }
  return if (entry == "个人") limits.copy(outputTokens = PERSONA_REPORT_OUTPUT_TOKENS, thinkingTokens = PERSONA_REPORT_THINKING_TOKENS) else limits
}

const val PERSONA_REPORT_OUTPUT_TOKENS = 8192
const val PERSONA_REPORT_THINKING_TOKENS = 4096

interface AiBudgetRepository {
  val books: Flow<AiBudgetBook>
  suspend fun initialize()
  suspend fun allowance(): Long
  suspend fun limits(entry: String = "主题"): AiRunLimits = AiRunLimits()
  suspend fun begin(conversation: String, previous: String? = null): String
  suspend fun decide(id: String, more: Boolean, amount: Long)
  // output 为本次运行实际下发的 max_tokens（正文与思考之和），缺省回退到当前档位。
  suspend fun reserve(analysis: String, input: Long, images: Int, retry: Boolean = false, output: Long? = null): String
  suspend fun change(update: (AiBudgetBook) -> AiBudgetBook)
}

@Singleton
class AiBudgetStore @Inject constructor(private val db: Ng2nDatabase, private val settings: AiSettingsStore) : AiBudgetRepository {
  private val initialization = Mutex()
  private var initialized = false
  // 旧账以美元记账，读取时即换算，任何一次写入都会把换算结果落盘。
  private fun decode(payload: String?): AiBudgetBook = (payload?.let { Json.decodeFromString<AiBudgetBook>(it) } ?: AiBudgetBook(currency = AI_CURRENCY)).convertLegacyUsd()
  override val books = db.aiBudgetDao().observe().map(::decode)
  override suspend fun change(update: (AiBudgetBook) -> AiBudgetBook) {
    try {
      db.withTransaction {
        db.aiBudgetDao().put(AiBudgetEntity(payload = Json.encodeToString(update(decode(db.aiBudgetDao().read())))))
      }
    } catch (e: CancellationException) { throw e }
    catch (e: AiBudgetExceeded) { throw e }
    catch (e: Exception) { throw AiStorageException(e) }
  }
  override suspend fun initialize() = initialization.withLock {
    if (!initialized) {
      val legacy = try { db.aiConversationDao().usageRecords() } catch (e: CancellationException) { throw e } catch (e: Exception) { throw AiStorageException(e) }
      change { book ->
        val imports = legacy.filter { old -> book.requests.none { it.id == "legacy:${old.requestId}" } }.map { old ->
          val price = AiPrice()
          AiBudgetRequest("legacy:${old.requestId}", "legacy:${old.runId}", old.conversationId, aiDay(old.startedAt),
            price.cost(1_000_000, 4096), price)
        }
        book.copy(requests = book.requests + imports).pending()
      }
      initialized = true
    }
  }
  override suspend fun allowance(): Long = when (settings.settings.first().allowance.wire) {
    "short" -> 200_000; "long" -> 1_000_000; "higher" -> 2_000_000; else -> 500_000
  }
  override suspend fun limits(entry: String): AiRunLimits = runLimitsFor(settings.settings.first().allowance.wire, entry)
  override suspend fun begin(conversation: String, previous: String?): String {
    initialize()
    if (previous != null && books.first().analyses.any { it.id == previous }) return previous
    val id = UUID.randomUUID().toString()
    val limit = allowance()
    change { it.copy(analyses = it.analyses + AiBudgetAnalysis(id, conversation, limit)) }
    return id
  }
  override suspend fun decide(id: String, more: Boolean, amount: Long) = change { book ->
    book.copy(analyses = book.analyses.map { if (it.id == id) it.copy(limit = if (more) Math.addExact(it.limit, amount) else it.limit, stopped = !more) else it })
  }
  override suspend fun reserve(analysis: String, input: Long, images: Int, retry: Boolean, output: Long?): String {
    val prefs = settings.settings.first()
    val id = UUID.randomUUID().toString()
    val time = System.currentTimeMillis()
    val price = AiPrice.at(time)
    val ceiling = output ?: runLimitsFor(prefs.allowance.wire).maxTokens.toLong()
    change { book -> book.reserve(AiBudgetRequest(id, analysis, book.analyses.single { it.id == analysis }.conversation,
      aiDay(time), price.cost(input, ceiling), price, images = images, retry = retry),
      if (prefs.dailyEnabled) prefs.dailyLimitFen?.times(10_000) else null) }
    return id
  }
}

class AiRunBudget(val store: AiBudgetRepository, val analysis: String) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<AiRunBudget>
  var requestId: String? = null
  var transportStarted = false
  var serverRejected = false
  var outputStarted = false
  var rawUsage: kotlinx.serialization.json.JsonObject? = null
  private var estimatedInput = 0L
  private var imageCount = 0
  var limits: AiRunLimits = AiRunLimits()
  private var toolCalls = 0
  private var repeats = 0
  var exhausted: String? = null
    private set
  private val seenTools = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
  var onRetry: suspend () -> Unit = {}
  fun prepare(input: Long, images: Int) { estimatedInput = input; imageCount = images }
  suspend fun reservePrepared() = reserve(estimatedInput, imageCount)
  suspend fun reserve(input: Long, images: Int) {
    currentCoroutineContext().ensureActive()
    finish()
    requestId = null
    transportStarted = false
    serverRejected = false
    estimatedInput = input
    imageCount = images
    rawUsage = null
    requestId = store.reserve(analysis, input, images, output = limits.maxTokens.toLong())
    outputStarted = false
  }
  // 执行上限不中止本次运行：标记之后工具一律拒绝执行，由运行图在同一次运行内收尾。
  suspend fun beforeTool(name: String = "", signature: String = name) {
    currentCoroutineContext().ensureActive()
    val book = store.books.first()
    val active = book.analyses.single { it.id == analysis }
    if (active.stopped || book.requests.filter { it.analysis == analysis }.sumOf { it.charged } >= active.limit) throw AiBudgetExceeded(false)
    if (exhausted != null) return
    if (++toolCalls > limits.toolCalls) { exhausted = "本次分析的工具执行次数已达上限（${limits.toolCalls} 次）"; return }
    if (seenTools.add(signature)) repeats = 0
    else if (++repeats >= limits.repeats) { exhausted = "连续 ${limits.repeats} 次重复读取同一资料，没有新增证据"; return }
    if (name in AI_WEB_TOOLS) requestId?.let { id -> store.change { it.webRead(id) } }
  }
  suspend fun retryAttempt() {
    currentCoroutineContext().ensureActive()
    finish()
    requestId = null
    transportStarted = false
    serverRejected = false
    onRetry()
    requestId = store.reserve(analysis, estimatedInput, imageCount, retry = true, output = limits.maxTokens.toLong())
    rawUsage = null
    outputStarted = false
  }
  suspend fun settle(input: Long, output: Long, cached: Long = 0) {
    requestId?.let { id -> store.change { it.settle(id, input, output, cached) } }
  }
  suspend fun sending() {
    currentCoroutineContext().ensureActive()
    requestId?.let { id -> store.change { it.sending(id) } }
    currentCoroutineContext().ensureActive()
    transportStarted = true
  }
  suspend fun finish() { requestId?.let { id -> withContext(NonCancellable) {
    store.change { when {
      serverRejected -> it.rejected(id)
      transportStarted -> it.pending(id)
      else -> it.releaseUnsent(id)
    } }
  } } }
}

class AiExecutionQueue {
  private val order = MutableStateFlow<List<String>>(emptyList())
  suspend fun enter(id: String, position: (Int) -> Unit) {
    order.update { it + id }
    try { order.onEach { position(it.indexOf(id)) }.first { it.firstOrNull() == id } }
    catch (e: CancellationException) { leave(id); throw e }
  }
  fun leave(id: String) { order.update { it - id } }
  companion object { val shared = AiExecutionQueue() }
}
