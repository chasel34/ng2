# 07 — 端点层(M1,25 个)

**What to build:** `docs/API文档.md` §12 + research/inventory.md §3.2 的 25 个端点全部落 Kotlin:全部默认 POST、业务参数进 URL query、表单只放表单字段;api 层不碰 method/format/host/auth/UA(100% 传输层决定,与 RN 版同构)。已知坑逐条带上:`removeTopicFavorite` 用 `tidarray`;`forum.php` `key` 与 block-word `data` 走 GBK;user-profile 带 referer;`setSubBoardOption` 参数名是动词;子版块 `attributes` 白名单**修掉魔法数误报**(spec §一.5);负数 fid 按有符号处理;热帖是客户端聚合(并发拉前 5 页、24h 窗口过滤重排)不是端点。各端点解析器配 `rejectNonTopicList` 式形状否决。

**Blocked by:** 04, 06

**Status:** open

- [ ] 各端点解析器金样本对拍通过
- [ ] 联网冒烟套件(默认跳过,`NGA_INTEGRATION=1` 开;沿用 app-v1 票 02 的模式与脱敏纪律)
- [ ] 写端点(8 个)全部带 operation=write 元数据(接票 06 的闸)
