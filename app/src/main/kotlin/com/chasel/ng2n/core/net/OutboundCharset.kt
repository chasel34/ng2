package com.chasel.ng2n.core.net

private const val INCHST_PARAM = "__inchst"

const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

const val FORM_CONTENT_TYPE_GBK = "$FORM_CONTENT_TYPE;charset=GBK"

fun inchstParam(requestQuery: QueryParams?): QueryValue? =
  if (hasGbkParam(requestQuery)) null else QueryValue.Text("UTF8")

fun formContentType(form: QueryParams?): String =
  if (hasGbkParam(form)) FORM_CONTENT_TYPE_GBK else FORM_CONTENT_TYPE

fun outboundQuery(requestQuery: QueryParams, formatParams: QueryParams = emptyMap()): QueryParams {
  val merged = LinkedHashMap<String, QueryValue?>(
    (requestQuery.size + formatParams.size + 1) * 2,
  )
  merged[INCHST_PARAM] = inchstParam(requestQuery)
  for ((key, value) in formatParams) merged[key] = value
  for ((key, value) in requestQuery) merged[key] = value
  return merged
}

fun outboundUrl(host: String, path: String, query: QueryParams): String {
  val trimmedHost = host.trimEnd('/')
  val queryString = buildQueryString(query)
  return if (queryString == "") "$trimmedHost/$path" else "$trimmedHost/$path?$queryString"
}
