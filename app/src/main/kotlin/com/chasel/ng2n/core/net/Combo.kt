package com.chasel.ng2n.core.net

/**
 * 「格式参数 × 域名」的组合空间与成功组合缓存。直译 `src/core/net/combo.ts`。
 */

/**
 * 一次尝试的「组合」——反封锁链前半段枚举的就是这个二维空间
 * (ADR-0002 / API 文档 §0.8:格式参数 × 域名)。
 */
data class FetchCombo(
  val format: ResponseFormat,
  val host: String,
)

/**
 * 默认参与轮换的格式档位。
 *
 * **只放 JSON 家族**(`__output=8` / `__output=11` / `lite=js`):三者洗出来的信封同构,
 * `core/api` 的字段遍历一行都不用改。API 文档 §0.8 里 MNGA 轮换的是 `lite=xml` ↔ `__output=10`,
 * 那是因为它下游是 XML 解析;本项目下游是 JSON,XML 档要等有了 XML→信封的转换才能进这个表。
 *
 * **`jsonVerbose`(`__output=11`)排在 `jsonLite` 前面是有事故出处的**(ADR-0002 第 8 条):
 * `fid=414`(游戏综合讨论)的 `thread.php` 响应里混进了**声明 GBK 却是 UTF-8 的字节**,
 * 还有 GBK / GB18030 都解不出的字节;解码器只能吐替换字符,替换字符又正好落在转义符上,
 * JSON 解析必挂。要命的是 `json`(`__output=8`)与 `jsonLite`(`lite=js`)**是同一份字节**,
 * 后者只多包一层 `window.script_muti_get_var_store=` —— 轮换表里那两档等于只有一档,
 * 换几个域名都是同样的坏字节,整个版块在 app 里永远打不开。而 `__output=11` 是
 * **另一个序列化器**,同一页 100 条主题解得干干净净。
 *
 * 教训:轮换档位的价值在于**它们不共用同一段服务端代码**,同源的两档凑不出冗余。
 */
val DEFAULT_ROTATION_FORMATS: List<ResponseFormat> = listOf(
  ResponseFormat.JSON,
  ResponseFormat.JSON_VERBOSE,
  ResponseFormat.JSON_LITE,
)

/**
 * 组合枚举的默认上限。5 域名 × 3 格式 = 15 次太狠,用户等不起。
 *
 * 枚举是「域名外层、格式内层」,所以这个数同时决定了**够几个域名**:
 * 8 次 = 前两个域名各试满 3 个格式,第三个域名再试 2 个。
 * 从 6 提到 8 是为了在加进 `jsonVerbose` 之后**保住原来的三域名覆盖**——
 * 维持 6 的话只够两个域名,等于拿「被封时换域名」换「坏字节时换格式」,两头都不该丢。
 */
const val DEFAULT_MAX_ATTEMPTS = 8

/** 这个格式档位现在有没有解析器(没有的不进轮换,交给链上后面的策略)。 */
fun isRotatableFormat(format: ResponseFormat): Boolean = format.isJson

/**
 * 成功组合的缓存 key,即「接口 key」(API 文档 §0.8)。
 *
 * 光用 path 不够:`nuke.php` 底下几十个 `__lib` / `__act`,被封的粒度是接口而不是脚本文件。
 *
 * ⚠️ 反过来说,`thread.php` 这一条是**版块列表 / 搜索 / 收藏夹 / 热帖 / 精华区 /
 * 某人的主题共用**的 —— 这是有意的(被封的粒度确实是接口),代价是一条记录坏掉
 * 会让这半个 app 一起失效,所以自愈([DEFAULT_COMBO_TTL_MS])与可观测性是它的配套条件。
 *
 * **不含 uid**:它记的是「服务端在哪个组合上没封这个接口」,与谁在用无关(RN 版同);
 * 会串账号的是数据层的业务缓存,那一层用 [NgaRequest.cacheScope]。
 */
fun interfaceKeyOf(request: NgaRequest): String {
  val parts = ArrayList<String>(2)
  for (name in listOf("__lib", "__act")) {
    val value = (request.query[name] as? QueryValue.Text)?.value
    if (!value.isNullOrEmpty()) parts.add("$name=$value")
  }
  return if (parts.isEmpty()) request.path else "${request.path}?${parts.joinToString("&")}"
}

/** 缓存里的一条:组合 + 记下来的时刻(用来判过期)。 */
data class ComboRecord(
  val combo: FetchCombo,
  /** 记住它的时刻(ms since epoch) */
  val at: Long,
)

/**
 * 成功组合缓存:哪个接口用哪个组合成功过,下次优先拿它开局。
 * 只活在内存里(同 MNGA)——被封是会变的,重启后重新试探比读旧值更稳,**故意不持久化**。
 */
interface ComboCache {
  fun get(key: String): FetchCombo?
  fun remember(key: String, combo: FetchCombo)
  fun forget(key: String)

  /**
   * 当前记着的全部组合(新的在后)。给实验室页的「本次运行的组合表」用——
   * 「这半个 app 现在挂在哪个组合上」是排障时最想知道的一件事。
   */
  fun entries(): List<Pair<String, ComboRecord>>
}

/**
 * 缓存条目的存活时间。
 *
 * **不能只靠「全组合都失败」来清缓存**(ADR-0002 第 2 条,2026-08-13「版块全空」排查):
 * 那条路只在组合明确失败时才走得到,一旦某个组合是「能解析但没有业务数据」这种半通不通的
 * 状态,缓存就再也没有出口,唯一的复位手段变成杀进程。给它一个保质期:过期后从默认顺序
 * (官方域名 + `__output=8`)重新试探一次,代价是一次请求,收益是能自愈。
 */
const val DEFAULT_COMBO_TTL_MS: Long = 10 * 60 * 1000

/**
 * 内存版组合缓存。
 *
 * 线程安全:反封锁链会被多个协程并发调用(首页同时拉几个版块),而 RN 那边是单线程 JS
 * 不用管。用 `synchronized` 而不是 `ConcurrentHashMap`:要的是保序遍历([entries] 给
 * 实验室页看)加过期时的读写原子性,量级只有十几条。
 *
 * @param ttlMs 条目存活时间,默认 10 分钟;传 [Long.MAX_VALUE] 关掉过期(单测用)。
 * @param now 取当前时刻,默认系统时钟(单测注入假时钟)。
 */
class InMemoryComboCache(
  private val ttlMs: Long = DEFAULT_COMBO_TTL_MS,
  private val now: () -> Long = System::currentTimeMillis,
) : ComboCache {

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

/**
 * 排出这次请求要依次尝试的组合。
 *
 * 顺序:缓存命中的组合 → 调用方指定的组合 → 域名外层 × 格式内层的笛卡尔积。
 * 格式放内层是因为换格式比换域名便宜(同一台服务器,连接还热着),
 * 而域名整体不可达时反正三种格式都会败,早点换域名也没用。
 *
 * @param requested 调用方自己指定的组合(`request.format` / `request.host`),排在轮换前面。
 * @param preferred 缓存里上次成功的组合,排最前。
 */
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

  // 调用方点名要 XML/HTML 时不轮换:那条路线上没有解析器,多打几次没意义,
  // 直接把这一个组合发出去,让它以 unavailable 落到链上后面的策略。
  if (requested != null && !isRotatableFormat(requested.format)) return listOf(requested)

  if (preferred != null && isRotatableFormat(preferred.format)) push(preferred)
  if (requested != null) push(requested)
  for (host in hosts) {
    for (format in rotatable) push(FetchCombo(format, host))
  }
  return combos.take(maxOf(1, maxAttempts))
}
