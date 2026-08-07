# 03 — BBCode → AST 解析器

**What to build:** core 层纯 TS 解析器:输入楼层 BBCode 原文,输出结构化 AST(ADR-0001)。覆盖功能文档 §2.9 标签并集:文字样式、结构(quote/code/collapse/list/table/align/标题/分割线)、链接引用(url/uid/tid/pid/@)、媒体(img/noimg/album/attach/flash)、表情、特殊(dice/lessernuke/hip/item/stripbr)。读取侧两轮 HTML 实体解码 + UTF-16 代理对还原。未知标签原样透传为文本节点。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] 每类标签的解析单测 + 嵌套/未闭合/未知标签容错单测通过
- [x] 两轮实体解码与代理对还原有专项单测(含 emoji)
- [x] 相对路径图片(`./` 开头)在 AST 中标记为待拼接附件域名,不硬编码域名

## Comments

### 实现摘要(2026-08-07)

全部落在 `src/core/bbcode/**`,零 RN 依赖、零新增依赖。94 个单测,`pnpm test` 与 `pnpm typecheck` 全绿。

**两个公开接口**(`src/core/bbcode/index.ts`):

- `parseBBCode(source: string): BBCodeNode[]` —— 任何输入都返回节点数组,不抛异常
- `unescapeNgaText(raw: string): string` —— 两轮实体解码 + 代理对还原,签名档/标题等地方也能单独用

**文件分工**:`types.ts`(AST 契约)、`tags.ts`(标签表,加标签只动这里)、`parse.ts`(扫描器)、`normalize.ts`、`internal.ts`(解析期中间节点)、`entities.ts`。

### AST 节点类型清单(26 种,`type` 字段可穷尽 switch)

全部是可 JSON 序列化的普通对象,有单测锁 `JSON.parse(JSON.stringify(ast))` 往返,可直接进帖子缓存。

| 分组 | 节点 `type` | 来源标签 |
|---|---|---|
| 文本 | `text` / `linebreak` | 正文、`<br/>`、`\n` |
| 文字样式 | `bold` `italic` `underline` `strike` | `[b] [i] [u] [del]` |
| | `color` `size` `font`(带 `value`) | `[color=] [size=] [font=]` |
| 结构 | `quote` | `[quote]` |
| | `code`(带 `value`,内部不解析标签) | `[code]` |
| | `collapse`(带可选 `title`) | `[collapse] [collapse=标题]` |
| | `list`(`ordered` + `items: 节点数组[]`) | `[list] [list=1] [*]` |
| | `table`(`rows[].cells[]`,含 `colspan`/`rowspan`/`width`) | `[table] [tr] [td]` |
| | `align`(`left`/`center`/`right`) | `[align=] [l] [r]` |
| | `heading` / `divider` | `[h]`、`===标题===`、`======` |
| 链接引用 | `link`(`href`) | `[url] [url=]` |
| | `userRef`(`uid`) / `topicRef`(`tid`) | `[uid] [tid]` |
| | `floorRef`(`pid` + `args`) | `[pid] [pid=a,b,c]` |
| | `mention`(`username`) | `[@用户名]`、`[@]名字[/@]` |
| 媒体 | `image`(`variant: img/noimg` + `src` + `needsAttachBase`) | `[img] [noimg]` |
| | `attach` / `flash`(`media: video/audio/flash`) | `[attach] [flash] [flash=video]` |
| | `album`(`value`) | `[album]` |
| 表情 | `smiley`(`code`) | `[s:分类:名称]`、`[s:数字]` |
| 特殊 | `dice`(`expression`) | `[dice]XdY[/dice]`、`[dice XdY]` |
| | `box`(`variant: lessernuke/hip/item`) | `[lessernuke] [hip] [item]` |

`[stripbr]` 不产生节点:去掉本层换行后把内容并入上层。未知标签(如 `[randomblock]`/`[style]`)连同闭标签原样透传为 `text`。

### 给下游的几个约定

- **表情**:节点只带 `code`(`[s:` 与 `]` 之间的原文),直接喂 `resolveSmiley(node.code)` 即可。分类/名称怎么切、查不到怎么兜底全归 ticket 06 —— 它照抄官方 `js_bbscode_core.js`,解析器不重复实现。已有接缝单测锁住:查不到时 `resolveSmiley` 还原的原文与楼层原始 BBCode 逐字相同。
- **附件域名**:`image`/`attach`/`flash` 共用 `AttachmentRef`(`src` + `needsAttachBase`)。带协议或 `//` 开头的算绝对地址;其余(含 `./xxx` 与裸文件名)一律 `needsAttachBase: true`,`./` 前缀已剥掉,渲染层拼 `__GLOBAL._ATTACH_BASE_VIEW` 的域名。有单测断言 AST 里出现不了任何域名字样。
- **骰子**:只给 `expression`,不复算结果(ticket 08 的活)。
- **容错**:未知标签原样透传;未闭合标签把开标签文本降级成 text、内容并入上层,一个字都不丢;`[tr]/[td]/[*]` 缺闭标签按自闭合处理(NGA 常态)。

### 遗留问题 / 边界

1. **缩略图后缀剥离(`.thumb_s/.medium/.thumb`)没做** —— 归 ticket 07(其 What-to-build 明写「img 图片含缩略图剥后缀与附件域名动态拼接」)。解析器保留原始 `src`。
2. **`[noimg]` 的 `mon_YYYYMM/DD/` 日期前缀没拼** —— 需要发帖时间,解析器拿不到。AST 用 `variant: 'noimg'` 打了标,前缀规则归渲染层。
3. **嵌套深度上限 64 层**,超出的开标签当普通文本。防的是畸形内容把递归归一化打爆栈(未加限制时 5000 层直接 `RangeError`)。真实楼层引用套引用十来层封顶。
4. **两轮实体解码是无条件的**,和 MNGA `unescape()` 一致。副作用:用户手打的字面量 `&amp;lt;` 会被解成 `<`。属于协议既定行为,不额外补偿。
5. **落单的 UTF-16 代理码元换成 U+FFFD**,避免非法字符串进 RN 文本渲染。
6. **投票不在本票**:`vote` 是楼层字段不是 BBCode,归 ticket 08。
7. `===标题===` / `======` 只在行首识别(标签本身不打断「行首」,所以 `[quote]===标题===` 认得出),行中间的等号当普通文本。
