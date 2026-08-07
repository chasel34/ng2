# 03 — BBCode → AST 解析器

**What to build:** core 层纯 TS 解析器:输入楼层 BBCode 原文,输出结构化 AST(ADR-0001)。覆盖功能文档 §2.9 标签并集:文字样式、结构(quote/code/collapse/list/table/align/标题/分割线)、链接引用(url/uid/tid/pid/@)、媒体(img/noimg/album/attach/flash)、表情、特殊(dice/lessernuke/hip/item/stripbr)。读取侧两轮 HTML 实体解码 + UTF-16 代理对还原。未知标签原样透传为文本节点。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 每类标签的解析单测 + 嵌套/未闭合/未知标签容错单测通过
- [ ] 两轮实体解码与代理对还原有专项单测(含 emoji)
- [ ] 相对路径图片(`./` 开头)在 AST 中标记为待拼接附件域名,不硬编码域名

## Comments
