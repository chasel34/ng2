package com.chasel.ng2n.core.net.encoding

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `decode-body` domain 全量对拍(票 03 的主验收项)。
 *
 * 语料含 6 份真机抓包,其中:
 * - `capture-thread-list-undeclared-gbk` —— thread.php 是全仓唯一不声明 charset 的,
 *   走的正是「先 UTF-8、有 U+FFFD 再 GB18030、按替换字符投票」那条;
 * - `capture-thread-list-414-broken-bytes` —— **服务端下发的字节本身就坏**,
 *   期望里就该有 U+FFFD(「fid=414 打不开」的根因,不是解码器的 bug);
 * - `gbk-*` 那几条锁的是 GB18030 的**框法**(退回流里重解 / `0x80` = €),
 *   JDK 的 CharsetDecoder 在这几处与 WHATWG 不一样,见 `Gb18030.kt` 文件头。
 */
class DecodeBodyGoldenTest {

  @Test
  fun `decode-body 金样本全量对拍`() = runGoldenDomain("decode-body") {
    fn("parseCharset") { case -> parseCharset(case.stringFieldOrNull("contentType")) }
    fn("decodeResponseBody") { case ->
      decodeResponseBody(case.bytesField(), case.stringFieldOrNull("contentType"))
    }
  }
}
