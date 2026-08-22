# 09 — BBCode 解析器(M2)

**What to build:** `core/bbcode/parse.ts` 直译 Kotlin:单遍扫描、显式帧栈、`MAX_NESTING_DEPTH=64`、**永不抛异常**(未知标签/不匹配闭合原样降级纯文本;EOF 未闭合保留开标签文本、子节点上提——内容永不丢失)。29 种节点(见 research/inventory.md §4)定义为 `@Serializable` 密封类(可 JSON 序列化,直接进 Room 帖子缓存)。HTML 实体**双重解码**(NGA 双重转义)、按 UTF-16 码元解十进制实体自然重组代理对、孤立代理清洗、命名实体仅六个;正文裸 HTML 除 `<br/>` 一律字面文本。不支持标签清单照抄(pre/hide/spoiler/randomblock/email 降级;防剧透=color=white 行为)。投票不进 AST(`Floor.vote` 的 `~` 分隔 kv 独立解析,票 10)。

**Blocked by:** 05

**Status:** open

- [ ] coverage 覆盖表 29 类型金样本全绿
- [ ] 全部 bbcode fixtures 对拍零差异
- [ ] 长正文性能抽查:最长 fixture 解析耗时记录进 Comments(为票 13 的后台一次性转换提供预算)
