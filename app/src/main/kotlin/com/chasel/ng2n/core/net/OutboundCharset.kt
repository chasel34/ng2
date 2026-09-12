package com.chasel.ng2n.core.net

/**
 * 出站请求的字符集声明——`buildUrl` / `runAttempt` 里可对拍的那一半。
 *
 * RN 版把这两条埋在 `core/net/strategies/attempt.ts` 的**未导出**函数 `buildUrl` 与
 * `runAttempt` 里(掺着 context / 凭证 / Referer,不是纯函数),所以进不了金样本。
 * 票 03 把它们抠成纯函数放这里,单测手工移植自 `fetcher.test.ts` / `search.test.ts` /
 * `block-word.test.ts` 的对应断言。票 06 拼 URL 时**直接用这里**,不要再抄一遍判据。
 *
 * 两条铁律(四个环节漏一处 = 「偶发乱码」级难查 bug,移植风险 TOP5 #3):
 * 1. **query 里出现任一 GBK 参数 → 撤掉 `__inchst=UTF8`**。`__inchst=UTF8` 是
 *    「本次请求的入参按 UTF-8 读」的声明,跟 Android 客户端的全 GBK 路线是两条路;
 *    混着来服务端会按 UTF-8 去解 GBK 字节,搜索关键词直接变乱码。
 * 2. **POST 表单里出现任一 GBK 参数 → Content-Type 补 `;charset=GBK`**。
 *
 * 判据各看各的:`__inchst` 只看 **query**,Content-Type 只看 **form**——
 * RN 版就是这么分开判的(`hasGbkParam(request.query)` / `hasGbkParam(request.form)`),
 * 一条请求完全可能 query 全 UTF-8 而 form 里带 GBK。
 */

/** 声明「入参按 UTF-8」的公共参数名。 */
private const val INCHST_PARAM = "__inchst"

/** `application/x-www-form-urlencoded`。 */
const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

/** 表单里有 GBK 值时的 Content-Type。 */
const val FORM_CONTENT_TYPE_GBK = "$FORM_CONTENT_TYPE;charset=GBK"

/**
 * `__inchst` 该取什么值:`UTF8`,或 `null` = 从 query 里整个删掉。
 *
 * 传进来的是**这次请求自己的 query**(不含格式档参数),与 RN 版一致。
 */
fun inchstParam(requestQuery: QueryParams?): QueryValue? =
  if (hasGbkParam(requestQuery)) null else QueryValue.Text("UTF8")

/** POST 表单体的 Content-Type。`form` 为 null(GET / 无表单)时给普通那档。 */
fun formContentType(form: QueryParams?): String =
  if (hasGbkParam(form)) FORM_CONTENT_TYPE_GBK else FORM_CONTENT_TYPE

/**
 * 拼出站 query:`__inchst` → 格式档参数 → 请求自己的参数。
 *
 * 顺序与覆盖语义照抄 RN 版的对象展开(`{ __inchst, ...formatParams, ...request.query }`):
 * 后面的同名键**覆盖值但位置留在第一次出现处**——`LinkedHashMap.put` 正好是这个语义。
 * 所以请求显式写了 `__inchst` 时,它盖掉这里算出来的值,但仍排在最前面。
 *
 * @param formatParams 格式档参数(`__output=8` / `lite=js` …),由票 06 的 `RESPONSE_FORMATS` 给。
 */
fun outboundQuery(requestQuery: QueryParams, formatParams: QueryParams = emptyMap()): QueryParams {
  val merged = LinkedHashMap<String, QueryValue?>(
    (requestQuery.size + formatParams.size + 1) * 2,
  )
  merged[INCHST_PARAM] = inchstParam(requestQuery)
  for ((key, value) in formatParams) merged[key] = value
  for ((key, value) in requestQuery) merged[key] = value
  return merged
}

/** `host/path?query`;query 为空时不带问号。host 尾部的 `/` 先剥掉,与 RN 版一致。 */
fun outboundUrl(host: String, path: String, query: QueryParams): String {
  val trimmedHost = host.trimEnd('/')
  val queryString = buildQueryString(query)
  return if (queryString == "") "$trimmedHost/$path" else "$trimmedHost/$path?$queryString"
}
