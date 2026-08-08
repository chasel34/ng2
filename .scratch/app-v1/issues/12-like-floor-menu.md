# 12 — 点赞/点踩 + 楼层菜单

**What to build:** 楼层点赞/点踩即时变色计数(乐观更新,失败回滚),按响应 delta 判定最终状态(NGA 赞踩是切换式);楼层菜单全量:支持/反对、只看此人(顶部过滤条+退出)、查看签名弹窗、收藏(联动 11 的对话框)、贴条/举报/屏蔽此人 toast 占位。

**Blocked by:** 07, 09

**Status:** resolved

- [x] 赞/踩/取消三种状态迁移与服务端 delta 语义一致(单测)
- [x] 只看此人后翻页仍保持过滤,退出恢复全楼
- [x] 主楼 pid=0 的赞踩正常

## Comments

- 赞踩接口落在 `src/core/api/topic-recommend.ts`:`postRecommend` 打 `nuke.php?__lib=topic_recommend&__act=add&value=<1|-1>&tid&pid`,delta 从 `data["1"] ?? data["0"]` 取;三个纯函数 `nextRecommendState` / `expectedRecommendDelta` / `recommendStateOf` 把切换式迁移与服务端 delta 语义锁在一起,单测互相对拍(`topic-recommend.test.ts`,含 pid=0 必须真的出现在 query 里的用例——`buildQueryString` 只剔空串/undefined,数字 0 保留)。
- 本会话赞踩标记在 `src/store/topic-recommend.ts` 的 `useFloorRecommend`:乐观更新按预测迁移先上屏,服务端 delta 回来校正,失败回滚;同楼层请求在途时再按是 no-op(NGA 赞踩没有幂等,两发并发会互相踩)。`recommendPidOf` 统一「主楼传 0」的口径,卡片与菜单读写同一份状态。
- 未登录点赞走 `showLoginPrompt`(snack 条带「去登录」)。
- 只看此人走服务端过滤:`fetchTopicDetail` 新增 `authorId` → `read.php&authorid=`,进 query key,翻页天然保持;退出恢复进入前那一页。过滤期间阅读进度暂停(楼号是过滤后口径)、resume 提示条隐藏。匿名用户没有数字 uid,toast「匿名用户无法只看」。
- 查看签名:`FloorUser` 新增 `signature`(`signature`/`sign` 字段,空串=没设置,fixture 有真样本),弹窗用 `BBCodeBody` 渲染,面板形状与通用对话框一致。
- 楼层菜单条目与顺序照设计稿 `MENUS.floor`(贴条/支持/反对/举报/查看签名/收藏/[空隙]只看此人/屏蔽此人),长按整卡或菜单钮都能开,弹出位置 `insets.top + 300`(设计稿 menuTop);收藏直接复用 11 票的 `FavoriteFolderDialog`,贴条/举报/屏蔽此人 toast「本版本未开放」。
