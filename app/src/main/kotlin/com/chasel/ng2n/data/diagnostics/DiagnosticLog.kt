package com.chasel.ng2n.data.diagnostics

import java.time.Instant

/**
 * 反封锁链诊断日志的**落盘形态与脱敏**(ADR-0002 的排障旁路)。
 *
 * ## 修 P1-04(审计 2026-08-20)
 *
 * RN 版的行为:`src/core/net/fetcher.ts` 把**所有不以 `__` 开头的 query 参数**原样塞进
 * 诊断记录,`src/store/diagnostics.ts` 明文写 MMKV,实验室页一个按钮就能走系统分享面板
 * 发出去。于是这些东西会跟着日志一起走:
 *
 * - `read.php` 的 `fav`(隐藏主题的访问码 —— 等于把门钥匙发出去);
 * - `thread.php` 的 `key`(搜索词)、`content`(搜正文标记);
 * - `nuke.php&__act=set_block_word` 的 `data`(**整张屏蔽词/屏蔽用户表**)。
 *
 * 这一版改成**默认拒绝**:只有结构性参数([SAFE_DIAGNOSTIC_PARAMS])能进日志,
 * 其余一律换成 `<redacted>`。**保留键名**是有意的 —— 排障要知道这次请求带没带 `fav`,
 * 但不需要知道它的值。审计建议的「按请求声明 safeDiagnosticParams」被收成中央白名单:
 * 一处漏声明就重新泄露一次,不能靠调用方自觉。
 *
 * 每次尝试那一行的 `uid` **保留**(RN 版的原行为):反封锁链的「换账号重试」那一档
 * 排障时必须知道用的哪个号,而 `cid` 从来不进日志(RN 版注释:「凭证不许出现在能导出的
 * 文件里」)。uid 是本机用户自己的账号,不是第三方隐私。
 *
 * 纯 Kotlin,零 Android 依赖;落盘在 [DiagnosticLogStore]。
 */

/**
 * 允许原样进日志的业务参数 —— 全是**结构性**的(定位到哪个版块/主题/页),
 * 不含凭证、搜索意图与个人偏好。
 *
 * 加新键之前先问一句:这个值发给别人看会不会难受?会就别加。
 */
val SAFE_DIAGNOSTIC_PARAMS: Set<String> = setOf(
  "tid",      // 主题 id
  "fid",      // 版块 id
  "stid",     // 合集 id
  "pid",      // 楼层 id
  "page",     // 页码
  "lite",     // 格式档位(反封锁链要看)
  "v2",       // read.php 的新版结构标记
  "opt",      // 主题列表排序位
  "order_by", // 排序
  "recommend",// 精华区标记
  "authorid", // 只看此人:是个公开 uid,且不带它就复现不了这次请求
)

/** 被挡下的参数在日志里长这样。保留键名、丢掉值。 */
const val REDACTED_PLACEHOLDER = "<redacted>"

/**
 * 参数脱敏:白名单之外一律只留键名。
 *
 * `__` 开头的框架参数在上游(fetcher)就已经被剔掉了,这里再挡一次不吃亏。
 */
fun redactDiagnosticParams(params: Map<String, String>): Map<String, String> =
  params.entries
    .filterNot { it.key.startsWith("__") }
    .associate { (key, value) ->
      key to if (key.lowercase() in SAFE_DIAGNOSTIC_PARAMS) value else REDACTED_PLACEHOLDER
    }

/** 一次尝试失败的原因,摊平成纯数据。 */
data class AttemptError(
  val kind: String,
  val message: String,
  val status: Int? = null,
)

/** 链上一次实际发出的 HTTP 尝试。成功的那次 [error] 为 null。 */
data class AttemptLog(
  val strategy: String,
  /** 格式参数档位,如 `json`(`__output=8`) */
  val format: String,
  val host: String,
  /** UA 档位名(`webview` / `windowsPhone` …) */
  val userAgent: String,
  /** 这次尝试用的账号 uid;游客为 null */
  val uid: String? = null,
  val error: AttemptError? = null,
)

/**
 * 成功那一次的落点摘要(RN 版 2026-08-13「版块全空」排查加的)。
 *
 * 以前只有整条链失败才留记录,于是**「链自认为成功、但拿回来的是一份空数据」
 * 这种静默降级完全不可观测**。这里记的是纯结构信息(哪个组合、`data` 顶层有哪些键、
 * 列表有几条),不含任何正文与凭证。
 */
data class OutcomeSummary(
  val strategy: String,
  val format: String,
  val host: String,
  /** `data` 顶层的键,超出上限就截断 */
  val keys: List<String>,
  /** 列表类接口的条数(`__T` / `__R` 的元素个数),不是列表就没有 */
  val rows: Int? = null,
)

data class DiagnosticRecord(
  /** 记录时刻(ms since epoch) */
  val at: Long,
  val path: String,
  /** 业务参数。**进这个类之前不必脱敏**,[formatDiagnostic] 会做 */
  val params: Map<String, String> = emptyMap(),
  /** 最终抛给调用方的错误说明;成功记录里是落点的一句话 */
  val message: String,
  val attempts: List<AttemptLog> = emptyList(),
  /** 有它就是一条**成功**记录;没有就是整条链失败 */
  val success: OutcomeSummary? = null,
)

/** 摘要里最多列几个业务参数 —— 设计稿那一行只放得下 tid/page 这种量级。 */
private const val SUMMARY_PARAM_LIMIT = 3

/**
 * 错误页上那一行诊断摘要(设计稿 isError:`tid=42800000 · page=1 · ua=app/1.0.0`)。
 * UA 取最后一次尝试的**档位名**而不是完整 UA 串 —— 一行放不下,且要排查的正是档位。
 *
 * 同样走脱敏:这一行也会被截图发出去。
 */
fun diagnosticSummary(record: DiagnosticRecord): String {
  val parts = redactDiagnosticParams(record.params).entries
    .take(SUMMARY_PARAM_LIMIT)
    .map { "${it.key}=${it.value}" }
    .toMutableList()
  record.attempts.lastOrNull()?.let { parts += "ua=${it.userAgent}" }
  return parts.joinToString(" · ")
}

private fun formatAttempt(attempt: AttemptLog, index: Int): String {
  val combo = "${attempt.format} @ ${attempt.host}"
  val who = if (attempt.uid == null) "游客" else "uid=${attempt.uid}"
  val error = attempt.error
  val result = if (error == null) {
    "ok"
  } else {
    "${error.kind}${if (error.status == null) "" else " ${error.status}"}: ${error.message}"
  }
  return "  ${index + 1}. [${attempt.strategy}] $combo ua=${attempt.userAgent} $who → $result"
}

/** 成功记录的落点那一行:用了哪个组合、拿回来什么形状。 */
fun formatOutcome(success: OutcomeSummary): String {
  val rows = if (success.rows == null) "" else " ${success.rows} 条"
  val keys = if (success.keys.isEmpty()) "(无字段)" else success.keys.joinToString(",")
  return "[${success.strategy}] ${success.format} @ ${success.host} → data{$keys}$rows"
}

/**
 * 落本地日志的文本形态。一条记录多行:首行是请求与最终结果,其后每行一次尝试。
 * 存文本而不是 JSON:这份日志的唯一消费者是人(实验室页导出后发给自己看)。
 *
 * **脱敏在这里做**,而不是指望调用方 —— 这是 P1-04 的收口点。
 */
fun formatDiagnostic(record: DiagnosticRecord): String {
  val query = redactDiagnosticParams(record.params).entries
    .joinToString("&") { "${it.key}=${it.value}" }
  val target = if (query == "") record.path else "${record.path}?$query"
  val verdict = if (record.success == null) {
    "失败:${record.message}"
  } else {
    "成功:${formatOutcome(record.success)}"
  }
  val head = "${Instant.ofEpochMilli(record.at)} $target $verdict"
  return (listOf(head) + record.attempts.mapIndexed { index, a -> formatAttempt(a, index) })
    .joinToString("\n")
}

/** 本地日志保留多少条。被封时一屏能连打十几条,留太多既没用又占地方。 */
const val DIAGNOSTIC_LOG_LIMIT = 50

/** 往日志里追加一条并裁到上限(最新的在最后)。 */
fun appendDiagnosticLog(
  log: List<String>,
  record: DiagnosticRecord,
  limit: Int = DIAGNOSTIC_LOG_LIMIT,
): List<String> {
  val next = log + formatDiagnostic(record)
  return if (next.size <= limit) next else next.takeLast(limit)
}
