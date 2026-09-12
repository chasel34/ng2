package com.chasel.ng2n.core.net

sealed interface HttpClassification {
  data class Ok(val envelope: NgaEnvelope) : HttpClassification

  data class Failed(val error: NgaError) : HttpClassification
}

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
