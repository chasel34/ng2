# 16 — 浏览历史 + 阅读进度

**What to build:** 浏览过的主题自动入历史(上限 200 条,LRU);历史页展示「读到 N 楼/读完」并可重新打开;再次进入某主题时顶部出现「上次读到第 N 楼」提示条,点「回到那里」跳转,可关闭;抽屉/列表菜单入口可达。纯本地,无服务端依赖。

**Blocked by:** 07

**Status:** resolved

- [x] 阅读进度随滚动实时更新,重进 app 不丢
- [x] 历史去重(同主题更新时间与楼层而非新增条目)
- [x] 提示条样式与出现/消失逻辑与设计稿一致

## Comments

### 实现摘要(2026-08-08)

`pnpm typecheck` 与 `pnpm test`(37 个文件 / 572 个单测)全绿。零新增依赖(`expo-sqlite` 已在 07 装好)。

**core 层(`src/core/local/history.ts`,零 RN 依赖,17 个单测)**

规则全在这里,store 只做搬运。列表是「新的在前」的有序数组——上限只有 200 条,数组比 Map 省事,还天然就是历史页要的展示顺序。

- `upsertHistory(entries, visit, now)` —— 去重与 LRU。同 tid **更新时间与资料并挪到最前,不新增条目**;超过 200 条把最老的挤出去,并在 `evictedTids` 里报出来让适配器连带删行。元数据新值优先、缺席时保留旧值:从第 2 页直接进来拿不到楼主名,不能把第一次记下的名字冲掉。
- `advanceHistoryFloor(entries, tid, lou, now)` —— 楼层**只前进不后退**。回头翻前几楼不该把「读到 96 楼」倒退成「读到 3 楼」,「读完」也不该因为回看一眼就丢。没前进时返回 `changed: false` **且原样返回入参数组**,适配器据此跳过 SQLite 写入——滚动回调触发得很勤,不能每次都写盘。
- `isHistoryFinished` / `historyProgressLabel` —— 「读完」/「读到 N 楼」/「读到主楼」。只有主楼的主题读过主楼就算读完。
- `pageOfFloor(lou, rowsPerPage)` —— 提示条跳转要先算目标楼在第几页。
- `formatHistoryTime(updatedAt, now)` —— 照设计稿示例行的口径(刚刚 / 12 分钟前 / 今天 20:14 / 昨天 23:41 / 前天 / 日期)。一小时内先走相对时间;再往前的「今天/昨天/前天」**按本地日历日算而不是 24 小时窗口**,不然凌晨 0 点后昨晚 9 点的记录会被叫成「今天」。

**store 层(`src/store/history.ts`)**

SQLite 适配器(spec §4:浏览历史属 expo-sqlite,不塞 MMKV)。内存数组是唯一事实来源,SQLite 只是它的落盘影子——读永远走 store,不查库。库名 `ng2.db`、表 `browse_history`(tid 主键 + `updated_at DESC` 索引),20 票的帖子缓存可共用这个库各建各的表。

每次变更只动一条,而且 upsert 与进度前进都会把它挪到最前,所以**只写第一行**,不用全表重写;淘汰与写入包在一个事务里。

**UI 层**

- `src/app/history.tsx` —— 历史页,照设计稿 `isSimpleList` 的 history 档:副标题条「本机记录 · 保留最近 200 条」+ 行列表,右侧一格是进度,点行重开主题(带 fav 码)。相对时间会过期,页面停留时每分钟刷一次基准。右上角 `delete_sweep` 清空。
- `src/app/topic/[tid].tsx` —— `useReadingProgress` 收敛三件事:拿到一页数据就登记历史;`onViewableItemsChanged` 把屏上最高楼层报给 store(阈值 20%);进场时若存档楼层 ≥ 1 就出提示条。翻页期间 `keepPreviousData` 还在展示旧页,跳转必须核对 `data.page` 才滚,不然会拿旧页的楼层号错滚一通;楼层被删导致 `lou` 有空洞时落到目标楼后面最近的一楼。
- `ResumeBanner` —— 照设计稿 `progressTip`:primary-c 底、圆角 12,进场 .28s 上浮淡入,关闭/跳转即消失无退场动画。

**入口**:设计稿的抽屉里没有浏览历史,它在 `MENUS.list`(版块列表页右上角菜单),位置在「24 小时热帖」之后、「精华区」之前——已按该顺序接上 `/history`。

**偏离设计稿一处**:清空历史设计稿是「立即清空 + 可撤销 toast」,Android 原生 toast 挂不了撤销按钮,改成 `Alert` 先问一句。

**修掉的一个错单测**:`formatHistoryTime` 那条「凌晨刚过零点」用例原本取昨晚 23:41 对零点后 00:10,只隔 29 分钟——「分钟前」分支合理地先命中,根本走不到日历日分支。改成隔 2 小时 29 分(21:41 → 00:10),这才真正区分得开日历日与 24 小时窗口;另补一条用例钉住「一小时内不因跨零点改口」。
