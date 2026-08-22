# 03 — 字符集策略(M1)

**What to build:** 用 JVM `Charset.forName("GB18030")` 替代 RN 版手写状态机,但**策略照抄**(research/inventory.md §3.3):
- 响应侧:有 charset 声明就信;无声明先试 UTF-8,出现 U+FFFD 再试 GB18030,按替换字符计数投票取少者。
- 出站侧:逐参数 `gbk()` 标记(目前只有 `forum.php` 的 `key`、block-word 的 `data`);GBK 表外字符(emoji)按 **UTF-16 码元逐个**写十进制 HTML 实体再 percent 编码;**任一 GBK 参数出现 → 整个请求撤掉 `__inchst=UTF8` 声明**、POST Content-Type 加 `;charset=GBK`。
四个环节漏一处 = 「偶发乱码」级难查 bug(移植风险 TOP5 #3)。

**Blocked by:** 01

**Status:** open

- [ ] decode-body 全部 fixtures 金样本对拍通过(依赖票 05 的管线,可先手工样本起步)
- [ ] emoji/表外字符出站编码与 TS 版逐字节一致
- [ ] `__inchst` 撤销与 Content-Type 切换的用例覆盖
