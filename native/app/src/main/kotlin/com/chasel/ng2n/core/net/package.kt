/**
 * `core/net` —— 传输层:OkHttp client 工厂、自管 CookieJar、拦截器链、反封锁链
 * (`renewTransport` 按请求派生独立连接池)、GB18030 逐参数编解码。
 *
 * 铁律:本包**禁止 import android.\***,好让 JVM 单测直接跑。票 06 / 03 的地盘。
 */
package com.chasel.ng2n.core.net
