package com.chasel.ng2n.core.net

/**
 * 「一次 HTTP 响应 → 信封或错误」的判定。
 *
 * TS 侧这段逻辑长在 `src/core/net/strategies/attempt.ts` 的下半截,和发请求、拼 URL、
 * 埋点混在一个 async 函数里,金样本导不出来(票 05 明确把策略链排除在外)。这里把**纯**的
 * 那一半抠出来:入参只有状态码与已解码的 body 文本,出参只有信封或 [NgaError],
 * 没有 IO、没有时序、没有状态,于是它的每条分支都能用普通单测钉住
 * (`ClassifyHttpResponseTest`,用例逐条移植自 `fetcher.test.ts`)。
 *
 * 票 06 的 `attempt` 只需要:发请求 → 解码 → 调本函数 → 按结果 report。
 */
sealed interface HttpClassification {
  /** 响应能解成信封(**包括 HTTP 非 2xx 但 body 是正常数据的情况**)。 */
  data class Ok(val envelope: NgaEnvelope) : HttpClassification

  /** 这一发失败了。`error.retryable` 决定反封锁链走不走下去。 */
  data class Failed(val error: NgaError) : HttpClassification
}

/**
 * HTTP 非 2xx 时 body 仍可能带有效错误信息,所以**先解析 body,body 为空才退回状态码报错**
 * (API 文档 §0.7)——非 2xx 但 body 有内容只是解析不了,那更像被封,要留 `parse` 这个信号。
 *
 * @param status HTTP 状态码。
 * @param bodyText 已按 `decodeResponseBody` 解码的响应体(票 03)。
 * @param via 触发本次尝试的策略名,原样带进错误里。
 * @param shape 该接口的信封形状(票 07 的 `NgaRequest.envelope`)。
 * @param validate 调用方的**一票否决**(ADR-0002 第 1 条):返回一段说明就表示
 *   「这不是我要的东西」,按 `kind = PARSE`(可重试)处理,于是它既不会被当成结果交出去,
 *   也不会被 format-rotation 记成「好组合」。返回 `null` = 认可这个响应。
 * @param parse 信封解析器,只为单测替换用;默认就是 [parseNgaJson]。
 */
fun classifyHttpResponse(
  status: Int,
  bodyText: String,
  via: String? = null,
  shape: EnvelopeShape = EnvelopeShape.WRAPPED,
  validate: (NgaEnvelope) -> String? = { null },
  parse: (String) -> NgaEnvelope = { parseNgaJson(it, via, shape) },
): HttpClassification {
  try {
    val envelope = parse(bodyText)
    val rejected = validate(envelope)
    if (rejected != null) {
      throw NgaError(NgaErrorKind.PARSE, rejected, status = status, via = via)
    }
    return HttpClassification.Ok(envelope)
  } catch (cause: Throwable) {
    if (cause is NgaError && cause.kind == NgaErrorKind.SERVER) {
      // 服务端说「未登录」而我们手上明明有凭证 = 这一发的身份没送到,不是语义错误。
      // 标成可重试,让 format-rotation 换下一个组合(判据见 AUTH_LEVEL_SERVER_MESSAGES)。
      // 顺带避开 rotation 里「服务端语义错误 = 这个组合是通的」那条缓存规则——
      // 否则丢身份的那个域名会被记住并继续用满一个缓存周期(2026-08-13 真机取证)。
      //
      // 写操作(签到 / 点赞 / 回帖)也走这条路,但不会重复提交:服务端回「未登录」
      // 就是它**拒绝**了这一发,没有副作用可言,换个域名重发才是用户要的结果。
      //
      // 游客态也要标成可重试——不是为了换域名(游客哪个域名都没 cookie,换了也白换,
      // 那一层在 format-rotation 里单独刹住),而是为了**别把整条链掐死**:
      // 链上后面还有网页兜底和帖子缓存,它们确实能把这一页拿出来(2026-08-13 真机取证:
      // 同一个帖子接口报未登录、点「用网页版打开」正文完整渲染)。
      if (isAuthLevelServerError(cause.text)) {
        return HttpClassification.Failed(
          NgaError(
            NgaErrorKind.SERVER,
            cause.text,
            code = cause.code,
            via = via,
            cause = cause,
            retryable = true,
          ),
        )
      }
      return HttpClassification.Failed(cause)
    }

    val statusFailed = status < 200 || status >= 300
    if (statusFailed && bodyText.jsTrim().isEmpty()) {
      return HttpClassification.Failed(
        NgaError(NgaErrorKind.HTTP, "HTTP $status", status = status, via = via, cause = cause),
      )
    }
    return HttpClassification.Failed(
      NgaError(
        NgaErrorKind.PARSE,
        if (cause is NgaError) cause.text else "响应解析失败",
        status = status,
        via = via,
        cause = cause,
      ),
    )
  }
}
