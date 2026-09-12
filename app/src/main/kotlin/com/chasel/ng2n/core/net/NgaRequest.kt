package com.chasel.ng2n.core.net

/**
 * 一次逻辑上的 NGA 读/写请求,与具体走哪条策略无关。直译 `src/core/net/types.ts`,
 * 外加**修 P1-01 / P1-02** 补上的读写元数据。
 */

/**
 * 这一发请求是读还是写。**没有缺省值——每个调用点都必须显式标注**。
 *
 * ## 为什么是「编译期强制标注」而不是「未标注按写处理」
 *
 * 票面给了两条路二选一。选强制标注,理由是**默认值会静默走错**:
 * - 「未标注 = 按写处理」意味着漏标一个读接口 → 它悄悄失去整条反封锁链,
 *   表现是「这个版块偶尔打不开」,和被封长得一模一样,极难归因;
 * - 「未标注 = 按读处理」就是 RN 版的现状,也就是 P1-01 那个 bug 本身。
 *
 * 而强制标注的代价只是编译错误 —— 25 个端点(inventory §3)一次性标完,
 * 之后新加端点漏标**编译不过**,不可能带着缺陷上线。
 *
 * Kotlin 侧的落地方式:[NgaRequest] 的这个构造参数**不给默认值**,
 * 于是任何 `NgaRequest(path = …)` 都编译不过。
 */
enum class Operation {
  /**
   * 只读。可以进格式轮换、可以换账号、失败可以重发 —— 重发没有副作用。
   */
  READ,

  /**
   * 写(签到 / 点赞 / 收藏 / 回帖 / 清通知……)。
   *
   * **禁入 format-rotation 与 switch-account,失败不自动重放**(P1-01):
   * 服务端可能已经执行了,只是响应丢在路上 —— 重发会产生重复收藏夹、把点赞翻回去,
   * 换账号更会把写入落到别人头上。链只有 direct 一档,超时/失败即终点。
   */
  WRITE,
  ;

  /** 这一档默认的账号策略。 */
  val defaultAccountPolicy: AccountPolicy
    get() = if (this == WRITE) AccountPolicy.PINNED else AccountPolicy.FALLBACK
}

/**
 * 这一发请求允不允许中途换身份(审计 P1-01 的整改建议)。
 *
 * - [PINNED]:身份在请求发起时**固定**,整个生命周期不得切换。写操作恒为这一档。
 * - [FALLBACK]:允许 switch-account 那一档拿下一个已登录账号重试一次。
 */
enum class AccountPolicy { PINNED, FALLBACK }

/**
 * 覆盖凭证的三态。
 *
 * TS 用 `NgaCredentials | null | undefined` 表达三态(`undefined` = 不覆盖、
 * `null` = 强制游客),Kotlin 里 `null` 只有一个,所以包一层:
 * 字段为 `null` = 不覆盖,`CredentialOverride(null)` = 强制游客。
 */
@JvmInline
value class CredentialOverride(val credential: Credential?) {
  companion object {
    /** 强制以游客身份发这一发。 */
    val GUEST = CredentialOverride(null)
  }
}

/**
 * 一次逻辑请求。
 *
 * 字段顺序与 `types.ts` 的 `NgaRequest` 对齐,[operation] 是新增的必填项(见上)。
 */
class NgaRequest(
  /** 端点路径,如 `thread.php`、`nuke.php`。 */
  val path: String,

  /**
   * 读还是写。**必填**,没有默认值 —— 漏标编译不过(见 [Operation])。
   */
  val operation: Operation,

  /** 业务参数,一律放 URL query(NGA 的惯例,API 文档 §0.4)。 */
  val query: QueryParams = emptyMap(),

  /** POST 表单字段,通常只有认证信息。 */
  val form: QueryParams = emptyMap(),

  /** 默认 POST(MNGA 的做法;Android 混用 GET/POST,效果相同)。 */
  val method: HttpMethod = HttpMethod.POST,

  /** 允不允许中途换身份。默认由 [operation] 推导:写 = PINNED,读 = FALLBACK。 */
  val accountPolicy: AccountPolicy = operation.defaultAccountPolicy,

  /** 点名要哪个格式档;不给就走轮换表。反封锁链会在这一维上交替。 */
  val format: ResponseFormat? = null,

  /**
   * 点名这次轮换用哪张格式表(不给就用 [DEFAULT_ROTATION_FORMATS])。
   * 给调用方一个「这个接口只有 `__output=11` 解得开」之类的出口。
   */
  val formats: List<ResponseFormat>? = null,

  /** 覆盖 UA 档位,例如 read.php 用 [UserAgentProfile.WINDOWS_PHONE]。 */
  val userAgent: UserAgentProfile? = null,

  /** 覆盖认证方式。 */
  val auth: AuthMode? = null,

  /** 覆盖账号(反封锁链「换账号重试」那一档要用)。三态见 [CredentialOverride]。 */
  val credential: CredentialOverride? = null,

  /** 覆盖域名。 */
  val host: String? = null,

  /**
   * 这个接口的信封形状,默认 [EnvelopeShape.WRAPPED](顶层有 `data` / `error` 壳)。
   * 顶层就是数据的接口显式写 `BARE`(见 `Envelope.kt` 为什么默认不能是它)。
   */
  val shape: EnvelopeShape = EnvelopeShape.WRAPPED,

  /**
   * 业务层的成功判据(ADR-0002 第 1 条,2026-08-13「版块全空」排查)。
   *
   * 反封锁链原本只认「洗得成 JSON」= 成功,于是一个**能解析但根本不是这个接口的响应**
   * 会被当成功、被记进成功组合缓存、并把「0 条数据」当结果交给 UI。给调用方一个
   * 一票否决权:返回一段说明就表示「这不是我要的东西」,链会把它当 `kind = PARSE`
   * (可重试)继续换下一个组合,坏组合也进不了缓存。
   *
   * 返回 `null` = 认可这个响应。**不要在这里做业务校验**(权限、空列表都属于正常结果),
   * 只判「形状对不对」。
   */
  val validate: ((NgaEnvelope) -> String?)? = null,

  /** 覆盖 Referer;`nuke.php?__lib=ucp` 必须带且需以 base url 开头。 */
  val referer: String? = null,

  /**
   * 同 [referer],但只写路径,由策略补上当前 host —— 调用方不必知道自己会被发到哪个域名
   * (反封锁链会换域名)。[referer] 已给出时以它为准。
   */
  val refererPath: String? = null,
) {

  /** 写操作吗。 */
  val isWrite: Boolean get() = operation == Operation.WRITE

  /**
   * 这条请求在**数据层查询缓存**里的作用域 key(**修 P1-02 的读侧那一半)。
   *
   * ## 为什么不是把 uid 拧进组合缓存
   *
   * 反封锁链的成功组合缓存(`interfaceKeyOf`)**故意不含 uid**:它记的是
   * 「这个接口在哪个格式 × 域名上没被封」,那是**服务端的封禁粒度**,与谁在用无关
   * (RN 版同样不含,本票不改这个粒度)。
   *
   * 会串账号的是**另一层**:数据层按 `['favorite-folders']` 这类全局 key 缓存业务结果
   * (审计 P1-02:切号后短暂显示上一账号的收藏夹)。那一层要的 key 是
   * 「谁 + 哪个接口 + 什么参数」,由票 07 / 16 拼,所以这里只提供一个**统一的拼法**,
   * 免得每个仓库各写各的、漏一个就串一次。
   *
   * `__` 开头的框架参数不进 key(它们是传输层自己拼的,和业务身份无关)。
   * 游客用固定的 `guest` 前缀 —— 游客的数据不该被任何一个账号复用,反之亦然。
   */
  fun cacheScope(uid: String?): String {
    val scope = if (uid.isNullOrEmpty()) "guest" else uid
    val business = query.entries
      .filterNot { it.key.startsWith("__") }
      .sortedBy { it.key }
      .mapNotNull { (key, value) -> queryScopeValue(value)?.let { "$key=$it" } }
      .joinToString("&")
    return if (business.isEmpty()) "$scope|$path" else "$scope|$path?$business"
  }
}

/** 缓存作用域里参数的字面值;被剔除的空值参数(见 `Query.kt`)不进 key。 */
private fun queryScopeValue(value: QueryValue?): String? = when (value) {
  null -> null
  is QueryValue.Flag -> if (value.value) "1" else null
  is QueryValue.Num -> value.value.toString()
  is QueryValue.Gbk -> value.value.ifEmpty { null }
  is QueryValue.Text -> value.value.ifEmpty { null }
}

/** 一次成功的请求结果。[via] 是产出它的策略名,便于排障与埋点。 */
class NgaResult(
  val envelope: NgaEnvelope,
  val via: String,
) {
  val root get() = envelope.root
  val data get() = envelope.data
  val time get() = envelope.time
  val fakeError get() = envelope.fakeError
}
