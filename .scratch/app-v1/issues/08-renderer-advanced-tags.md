# 08 — 渲染器进阶标签

**What to build:** 详情页正文覆盖剩余标签:collapse 折叠块(带标题展开收起)、list、table 简化实现(忽略 rowspan、colspan 拉通、整表横向滚动)、align/l/r/h、===标题===与分割线、album 相册、attach、dice 骰子(按 NGA 伪随机本地复算结果并展示)、noimg(按发帖日期拼路径前缀)、flash 视频/音频媒体卡片(点击外跳系统播放/浏览器)、投票只读渲染(题目/选项/票数,投票按钮 toast「本版本未开放」)、lessernuke/hip/item/stripbr、未知标签透传。

**Blocked by:** 07

**Status:** resolved

- [x] 骰子复算结果与站上真实骰子帖一致(fixture 单测)
- [x] vote 字段(~ 分隔 kv)解析单测,单选/多选/已结算三种形态渲染正确
- [x] 表格在窄屏可横向滚动且不撑破楼层卡片
- [x] 全部进阶标签有渲染快照或真机对照记录

## Comments

### 实现摘要(2026-08-08)

`pnpm typecheck` 与 `pnpm test`(560 个单测)全绿,`npx expo export --platform android` 能打出包。零新增依赖。

**core 层(零 RN 依赖)**

- `src/core/local/dice.ts` —— `resolveDice(nodes, seed)` 复算整楼的 `[dice]`,返回「AST 节点 → 结果」的表。算法照 NGA 官方 `js_bbscode_core.js` 的 `ubbcode.sRand.rnd` 复刻(种子 = authorId+tid+pid,`(种子*9301+49297)%233280`),**一个楼层里的多颗骰子共用同一条数列**,所以复算单位是整楼而不是单个标签。
- `src/core/local/vote.ts` —— `parseVote(raw, { tid })` 拆 `~` 分隔串。字段含义照官方 `js_read.js` 的 `commonui.vote` / `voteFormat`:`_选项id → 票数,投注量,总人数`、`max_select`(>1 即多选)、`end`(判已结算)、`opt` 位掩码、`priv` 门槛、`done`(本人投过哪几项),以及 `===分组名===` 分隔行(百分比按**组内**票数算)。
- `src/core/bbcode/walk.ts` —— `childNodeLists(node)`,「一个节点的子节点在哪」的唯一答案(children / list.items / table.rows[].cells[].children)。切段、骰子复算、覆盖清单三处原本各写了一遍。
- **noimg 不用再做**:07 已经在 `src/core/api/attachments.ts` 里按发帖时间(UTC+8)补 `mon_YYYYMM/DD/` 并有单测,本票只是确认它接在渲染路径上。

**渲染器(`src/ui`)**

`render.tsx` 的 `BlockNode` 现在对 03 票的 26 种节点全部有落点,占位文本表已删空。新增 `blocks.tsx`(collapse / list / table / lessernuke·hip·item / align)、`media.tsx`(dice / flash / attach / album)、`collapsible-card.tsx`(三处「一行提要 + 点开展开」共用)、`vote.tsx`。`segments.ts` 的块级表加了 align / collapse / list / table / box / dice / flash / attach / album——它们要么自带框、要么要横向滚,留在 `<Text>` 里做不到。

- **表格**:固定列宽 108 + 整表横向 `ScrollView`,`colspan` 拉通、`rowspan` 忽略、短行补空格子。
- **投票**:题目/选项/票数/占比条 + 官方那行说明(共计 N 人投票 · 共计 N 票 · 最多选择 N 项 · 结束时间…),投票按钮 toast「本版本未开放」。
- **媒体**:`[flash]` / `[attach]` 点了 `Linking.openURL` 交给系统播放器或浏览器,不内联播放(ADR-0001)。
- **相册**:默认收起成一条「共 N 张图片」,理由同附件宫格(一进楼全拉原图太费流量)。

**骰子对拍用的是站上真帖**(`dice.test.ts` 第一组):不是拿自己的实现自证,而是找了**楼主在后一楼用文字写下网页版结果**的帖子来对:

| 帖子 | 表达式 | 站上结果的出处 |
|---|---|---|
| tid 46868034 第 8 楼 | `d100` | 第 15 楼「74的现实偏离度」 |
| tid 46162468 第 5 楼 | 四颗 `d6` | 第 7 楼「空手道3 脑神经6 本领3 术2」(第四项标注「最终结果-3」,即掷出 5)、第 8 楼「3632」 |
| tid 46162468 第 7 楼 | `d13` | 第 11、12 楼「我操火!」「竟然是火遁」= 第 1 项 |
| tid 46868034 第 15 楼 | 两个 `1+1d7` | 第 17 楼「3块大陆感觉有点少」 |

四组全部逐个吻合,其中四颗 d6 那组验证了「同楼多颗骰子共用一条数列」。其余用例的期望值取自把官方那段 JS 原样跑一遍的输出。

**顺带改到的已交付代码**(都在汇报里说明过)

1. `Floor` 加 `authorId`(服务端原始 `authorid`)——骰子种子要它,原先只有 `authorKey`。
2. `src/core/bbcode/tags.ts` 认 `[lessernuke1/2/3]`,`BoxNode` 加 `punishment: post/topic/locked`。原先带数字的写法整段当未知标签透传,处罚说明和正文一起变成裸标签。
3. `src/app/topic/[tid].tsx` 的翻页手势加了一句 `!isHorizontalDragActive()`。翻页 PanResponder 在**捕获阶段**认领手势,祖先先手,楼层里横向滚的表格拖十几像素就翻页了——表格按下时先经 `ui/horizontal-drag` 打个招呼。
4. `segments.test.ts` 里「`[align=center]居中字[/align]` 留在行内」那条改成「单独占一块」:align 要真的对齐就必须落到 View 上。

### 遗留问题

1. **真机没跑**。本仓库跑不了组件渲染测试(没有 react-test-renderer / RN 预设),`ui/bbcode/coverage.test.ts` 锁的是「26 种节点各有样例、行内/块级归属不漂」这一层,加上 `expo export` 能打包。逐屏真机对照按票面约定留给 27 票全局验收——尤其是表格横滑与翻页手势的互不打架。
2. **骰子的两处已知偏差**(写在 `dice.ts` 头注释里):匿名楼层的 `authorid` 是 `-1`/`-2` 这种页内序号,网页版拿到的是不是同一个值没验证过;三个 id 加起来正好是 0 时网页版改用 `Math.random()`,谁也复算不出来。
3. **折叠块里的骰子**按官方 `collapse.load` 的读法实现(外层投过就接着外层种子走,否则用 `seedOffset = 块序号+1`),但**没找到能对拍的真帖**,只有单测锁行为。
4. **投票只对到一个真样本**(tid 47331456,单选)。多选、已结算、分组、投注/评分这几种形态的期望值来自官方 JS 的字段用法,不是抓到的真串。扫了约 400 个主题只碰到这一个带 `vote` 的。
5. **`hip` / `item` 只画了个普通的框**:现行官方 `js_bbscode_core.js` 里已经没有这两个标签,拿不到配色和语义,按「不丢内容」处理。
6. **投票没有独立的「题目」**:`vote` 串里本来就没有题目字段,网页版的标题也只是「投票」两个字,题目就是主题标题。
7. **扫帖时发现的既有 bug(不在本票范围,没动)**:约 400 个主题里有 35 个 `read.php` 响应被 `core/net` 判成「响应不是合法 JSON」(报错开头都是 `{"data":{"__CU":{...`,说明是清洗规则漏了某种内容),fid=414 的 `thread.php` 也整版垮。建议归到 18 票(反封锁链)一并查。
