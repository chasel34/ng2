package com.chasel.ng2n.core.net

enum class NetFixture(val file: String, val contentType: String, val note: String) {
  NOTI_EMPTY(
    "noti-empty.gbk.bin",
    "text/javascript; charset=GBK",
    "nuke.php __lib=noti __act=get_all,登录态,无未读",
  ),

  THREAD_LIST(
    "thread-list-fid650.gbk.bin",
    "text/html",
    "thread.php fid=650 page=1 __output=8;Content-Type 没带 charset,必须靠回落解码",
  ),

  UCP_USER(
    "ucp-user-41417929.gbk.bin",
    "text/javascript; charset=GBK",
    "nuke.php __lib=ucp __act=get uid=41417929",
  ),

  UCP_NOT_FOUND(
    "ucp-not-found.gbk.bin",
    "text/javascript; charset=GBK",
    "不存在的 uid,命中假错误白名单",
  ),

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

  fun response(status: Int = 200): FakeResponse =
    FakeResponse(status = status, contentType = contentType, body = bytes())
}
