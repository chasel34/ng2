# 06 — 表情资产管线

**What to build:** 一个可重跑的下载脚本从 NGA CDN 抓全 ~240 张表情打包进 assets;一个查表模块把 `[s:分类:名称]`(含 `[s:数字]` 默认套)解析为本地资源,查不到时回退远程 URL,再查不到显示原文。映射表从 NGA 官方前端 JS 提取(禁止从 GPL 仓库复制);注意 pst 分类文件名前缀是 pt 的坑;核实「熊猫」套当前是否存在。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 脚本幂等可重跑,产出清单文件记录数量与缺失项
- [ ] 查表模块单测:六套分类 + 数字默认套 + 未知表情回退
- [ ] 「熊猫」套存在性有结论并记录在本票 Comments

## Comments
