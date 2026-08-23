# 18 — 模拟器功能验收(M4)

**What to build:** 从 research/inventory.md §1-2 生成 **24 屏功能 checklist**(逐屏列功能点与预期,含写操作项用测试账号轻量验证、桩项验 toast);Pixel_8 AVD 全量走查。纪律:uiautomator 优先、截图循环放 subagent(token 纪律);**NGA 限流冷却**——连续冷启 ≥60s 间隔,成片网络失败先怀疑测量者自己;模拟器**只裁功能永不裁性能**。发现的缺陷回填成新票(20 起编号),修完复验,checklist 全绿本票才 resolved。

**Blocked by:** 13, 16, 17

**Status:** in-review

- [x] checklist 文档落 `.scratch/native-rewrite/acceptance/functional-checklist.md`
- [x] 全部 24 屏走查完成,结果逐项记录
- [ ] 缺陷票清零(或余项经所有者豁免并记录)

## Comments

**主控(2026-08-23)**:A/B/C 三段模拟器走查完成(`acceptance/functional-checklist.md`),缺陷票 20–34 共 15 张全部修复合并并复验(25 判为假缺陷)。checklist 160 项:通过 132,剩 28 项 —— 27 项「待所有者」(需真实 NGA 登录:签到、版块/主题收藏写、通知、账号切换、用户资料、我的主题/回复、搜索版块等),1 项双指缩放待真机(票 19)。本票转 in-review,等所有者登录验收批完成后 resolved。
