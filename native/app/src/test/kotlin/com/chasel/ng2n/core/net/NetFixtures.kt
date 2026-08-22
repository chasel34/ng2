package com.chasel.ng2n.core.net

/**
 * 真实抓包样本(2026-08-07 / 08-08,bbs.nga.cn),从 `src/core/net/__fixtures__` 原样搬过来。
 *
 * 文件存的是**原始响应字节**(多数是 GBK),不是 UTF-8 文本 —— 解码本身就是被测对象。
 * 已脱敏:抓包账号的 uid 统一替换成 10000001,用户名替换成 `nga_user`;
 * cookie / cid 不在响应体里,抓包时也没有落盘。
 *
 * Web 反解那四份(50KB × 3)**没有搬**:它们是票 08 的原料,这一票只落反解器的注入点。
 */
enum class NetFixture(val file: String, val contentType: String, val note: String) {
  /** nuke.php?__lib=noti&__act=get_all —— 没有新通知时的空 data */
  NOTI_EMPTY(
    "noti-empty.gbk.bin",
    "text/javascript; charset=GBK",
    "nuke.php __lib=noti __act=get_all,登录态,无未读",
  ),

  /** thread.php?fid=650 —— 唯一一条服务端**没声明 charset** 的,body 是 GBK */
  THREAD_LIST(
    "thread-list-fid650.gbk.bin",
    "text/html",
    "thread.php fid=650 page=1 __output=8;Content-Type 没带 charset,必须靠回落解码",
  ),

  /** nuke.php?__lib=ucp&__act=get —— 正常用户资料 */
  UCP_USER(
    "ucp-user-41417929.gbk.bin",
    "text/javascript; charset=GBK",
    "nuke.php __lib=ucp __act=get uid=41417929",
  ),

  /** 假错误:找不到用户(白名单里,要当成功) */
  UCP_NOT_FOUND(
    "ucp-not-found.gbk.bin",
    "text/javascript; charset=GBK",
    "不存在的 uid,命中假错误白名单",
  ),

  /** 真错误:找不到主题 */
  READ_THREAD_NOT_FOUND(
    "read-thread-not-found.gbk.bin",
    "text/javascript; charset=GBK",
    "read.php tid=1,真错误 2048:找不到主题",
  ),
  ;

  fun bytes(): ByteArray {
    val path = "fixtures/net/$file"
    val loader = NetFixture::class.java.classLoader ?: throw AssertionError("拿不到 classloader")
    val stream = loader.getResourceAsStream(path)
      ?: throw AssertionError("classpath 上找不到 $path")
    return stream.use { it.readBytes() }
  }

  /** 直接当一份假响应用。 */
  fun response(status: Int = 200): FakeResponse =
    FakeResponse(status = status, contentType = contentType, body = bytes())
}
