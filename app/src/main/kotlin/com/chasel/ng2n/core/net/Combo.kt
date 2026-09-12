package com.chasel.ng2n.core.net

data class FetchCombo(
  val format: ResponseFormat,
  val host: String,
)

val DEFAULT_ROTATION_FORMATS: List<ResponseFormat> = listOf(
  ResponseFormat.JSON,
  ResponseFormat.JSON_VERBOSE,
  ResponseFormat.JSON_LITE,
)

const val DEFAULT_MAX_ATTEMPTS = 8

fun isRotatableFormat(format: ResponseFormat): Boolean = format.isJson

fun interfaceKeyOf(request: NgaRequest): String {
  val parts = ArrayList<String>(2)
  for (name in listOf("__lib", "__act")) {
    val value = (request.query[name] as? QueryValue.Text)?.value
    if (!value.isNullOrEmpty()) parts.add("$name=$value")
  }
  return if (parts.isEmpty()) request.path else "${request.path}?${parts.joinToString("&")}"
}

data class ComboRecord(
  val combo: FetchCombo,
  val at: Long,
)

interface ComboCache {
  fun get(key: String): FetchCombo?
  fun remember(key: String, combo: FetchCombo)
  fun forget(key: String)

  fun entries(): List<Pair<String, ComboRecord>>
}

const val DEFAULT_COMBO_TTL_MS: Long = 10 * 60 * 1000

class InMemoryComboCache(
  private val ttlMs: Long = DEFAULT_COMBO_TTL_MS,
  private val now: () -> Long = System::currentTimeMillis,
) : ComboCache {

  // 遍历须保序，过期检查与删除须和其他读写互斥。
  private val lock = Any()
  private val map = LinkedHashMap<String, ComboRecord>()

  private fun live(key: String): ComboRecord? {
    val record = map[key] ?: return null
    if (now() - record.at < ttlMs) return record
    map.remove(key)
    return null
  }

  override fun get(key: String): FetchCombo? = synchronized(lock) { live(key)?.combo }

  override fun remember(key: String, combo: FetchCombo) {
    synchronized(lock) { map[key] = ComboRecord(combo, now()) }
  }

  override fun forget(key: String) {
    synchronized(lock) { map.remove(key) }
  }

  override fun entries(): List<Pair<String, ComboRecord>> = synchronized(lock) {
    map.keys.toList().mapNotNull { key -> live(key)?.let { key to it } }
  }
}

fun enumerateCombos(
  formats: List<ResponseFormat>,
  hosts: List<String>,
  maxAttempts: Int,
  requested: FetchCombo? = null,
  preferred: FetchCombo? = null,
): List<FetchCombo> {
  val rotatable = formats.filter(::isRotatableFormat)
  val seen = HashSet<FetchCombo>()
  val combos = ArrayList<FetchCombo>()
  fun push(combo: FetchCombo) {
    if (seen.add(combo)) combos.add(combo)
  }

  if (requested != null && !isRotatableFormat(requested.format)) return listOf(requested)

  if (preferred != null && isRotatableFormat(preferred.format)) push(preferred)
  if (requested != null) push(requested)
  for (host in hosts) {
    for (format in rotatable) push(FetchCombo(format, host))
  }
  return combos.take(maxOf(1, maxAttempts))
}
