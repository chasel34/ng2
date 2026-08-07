# 06 — 表情资产管线

**What to build:** 一个可重跑的下载脚本从 NGA CDN 抓全 ~240 张表情打包进 assets;一个查表模块把 `[s:分类:名称]`(含 `[s:数字]` 默认套)解析为本地资源,查不到时回退远程 URL,再查不到显示原文。映射表从 NGA 官方前端 JS 提取(禁止从 GPL 仓库复制);注意 pst 分类文件名前缀是 pt 的坑;核实「熊猫」套当前是否存在。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] 脚本幂等可重跑,产出清单文件记录数量与缺失项
- [x] 查表模块单测:六套分类 + 数字默认套 + 未知表情回退
- [x] 「熊猫」套存在性有结论并记录在本票 Comments

## Comments

### 实现摘要

**数据来源(法务)**:映射表从 `https://img4.nga.cn/common_res/js_bbscode_core.js` 的 `ubbcode.smiles` 现抓提取,没碰 GPL-2.0 的 Justwen 仓库。脚本路径是从登录态的 `bbs.nga.cn/thread.php?fid=-7` 页面内联 JS 里的 `__COMMONRES_PATH` 找出来的。同一段内联 JS 给出 `__IMGPATH = 'http://img4.nga.cn/ngabbs'`(调研报告里写的 `img4.nga.178.com` 也通),取图目录 `{IMGPATH}/post/smile/`,落库时升级成 https。

**「熊猫」套结论:线上已无此套。** 当前 `ubbcode.smiles` 的 key 恰好是 `0 / ac / a2 / ng / pst / dt / pg` 七个,全表检索无 `熊猫` / `xiongmao` 命中。调研报告里旧 Android 客户端 `assets/xiongmao/`(54 张)是官方前端下线后留在客户端里的历史遗留,不再补抓。反过来多出一套报告没覆盖的 **`ng`(NG娘,34 张)**,对应 v4 delta 报告提到的 mlzzen PR,已一并纳入。

**实际下载:265 条映射 / 265 个唯一文件 / 全部成功 / 缺失 0**(1.1 MB)。文件名全站唯一,故 `assets/smilies/` 扁平存放,与 CDN 目录一一对应。

| 套系 | key | 数量 | 文件名样例 |
| --- | --- | --- | --- |
| 默认 | `0` | 27 | `smile.gif` |
| AC娘(v1) | `ac` | 45 | `ac0.png` |
| AC娘(v2) | `a2` | 46 | `a2_02.png` |
| NG娘 | `ng` | 34 | `ng_1.png` |
| 潘斯特 | `pst` | 65 | `pt00.png` |
| 外域三人组 | `dt` | 33 | `dt01.png` |
| 企鹅 | `pg` | 15 | `pg01.png` |

`pst` → `pt` 前缀的坑确认存在,但**不需要特判**:官方表里存的就是 `pt00.png` 这样的成品文件名,照抄即可。

**交付文件**

- `scripts/fetch-smilies.mjs` — 纯 Node,零新依赖。幂等:已存在且非空的图片跳过,只补缺失;生成物不含时间戳,内容不变就不落盘。`--force` 全量重下,`--source <path>` 用本地 GBK 副本离线解析。写图片先落 `.part` 再 rename,并校验 PNG/GIF/JPEG 文件头,避免 CDN 用 200 返回错误页时写进一张打不开的"表情"。有缺失时 exit 1。
- `scripts/lib/parse-smilies.mjs` — 解析 `ubbcode.smiles`。表里混着单双引号、裸数字键、制表符对齐,还有 `//____display:'茶	ac	…'` 这种带引号的行内注释,所以不 eval,而是用尊重字符串字面量的扫描剥注释 + 花括号配对取块。
- `assets/smilies/*.png|gif` + `manifest.json`(套系/数量/缺失项/孤儿文件)。
- `src/core/smilies/` — 零 RN 依赖的查表逻辑,`resolveSmiley(code)` 三级兜底:`bundled` → `remote` → `unresolved`。分类/名称的切法照抄官方 `[smile]` 分支(纯数字走默认套;否则 `split(':')` 取前两段;分类为空退回默认套),含 `[s:0]` 判为假、`[s::1]` 走默认套这些边角。
- `src/ui/smilies.generated.ts` — metro 要的字面量 require 全表展开,值类型 `number | undefined`,渲染侧一句 `SMILEY_ASSETS[file] ?? { uri: remoteUrl }` 就把内置/远程两条路合并了。

**验证**:20 条单测(六套分类各一 + 整表 265 条全查得到 + 数字默认套 + `pst`→`pt` + 远程回退 + 四类查不到的原文标记 + 一条钉死的字面量 URL);`pnpm typecheck` 干净;全量 `pnpm test` 246 passed。脚本连跑三次确认"生成物无变化",删图/置空后重跑能精确补回。

### 遗留问题

1. **没加 npm script**:并行纪律禁改 `package.json`,重跑得敲 `node scripts/fetch-smilies.mjs`。建议后续补一条 `"smilies": "node scripts/fetch-smilies.mjs"`。
2. **CONTEXT.md 没收术语**:「表情 / 套系 / 默认套」没进术语表(同样是并行禁改范围),建议 27 打磨时补。
3. **畸形输入有意偏离官方**:官方 `parseInt('12abc',10)` 为真会渲染出一个坏 `<img>`,本实现要求 `^\d+$`,这类输入返回原文标记。更安全,但与官方渲染不像素级一致。
4. **夜间反色未处理**:旧客户端给 `ac`/`a2` 两套加 `invertFilter` 夜间反色。本票只管查表,`ResolvedSmiley` 带了 `category`,反色策略留给渲染侧(07/08)。
5. **表情显示尺寸**:调研报告提到旧客户端表情尺寸可调(默认 150,范围 1~200),属设置项(22),本票未涉及。
6. **孤儿文件只报不删**:官方哪天下线某个表情,`manifest.json` 的 `stray` 会列出来,但脚本不自动删,免得误伤。
