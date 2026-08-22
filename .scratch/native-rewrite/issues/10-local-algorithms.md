# 10 — 逆向本地算法包(M2;怪癖勿修)

**What to build:** 全部直译,**NGA 就这样,别修**(research/inventory.md §4):
- **骰子**:种子=authorId+tid+pid;LCG `state=(state*9301+49297)%233280`;宽松正则文法照抄;每项 ≤10 骰、≤100000 面;**一楼内所有 [dice] 共用一条随机流按文档顺序推进**;collapse 内 seedOffset 仅 tid>10246184。
- **匿名还原**:`#anony_<32hex>`;22 字天干地支 + 255 字百家姓;6 段固定偏移、**hex[5] 静默跳过**;255 表整字节索引越界掉字符照抄;颜色 hex[11:17]/[17:23]。
- **彩色标题**:掩码 1红2蓝4绿8橙16银32粗64斜128下划线;颜色互斥按优先级 if/else 链,样式叠加;`topic_misc` 无 padding base64 TLV(type+大端 u32;0 结束/1 掩码/2 stid/3 sfid)**优先于 titlefont**;sfid 有符号修正只对 fid/sfid、绝不对 stid/tid。
- **附件 URL**:base 从 `__GLOBAL._ATTACH_BASE_VIEW` 动态取(兜底 img.nga.cn/attachments);noimg 缺日期目录按 postedAt **固定 UTC+8** 合成;死域名替换;四种缩略后缀。
- **深链解析**:read.php/thread.php 映射、`&amp;`、percent、`#pid123Anchor`、foreign-host 拒绝;同一映射表服务系统深链与「由 URL 读取」。
- **投票解析**:`Floor.vote` 的 `~` 分隔 kv → 只读渲染模型。

**Blocked by:** 05

**Status:** open

- [ ] 各算法全部 fixtures 金样本对拍零差异(尤其骰子共享随机流、匿名越界 case、TLV 边界)
