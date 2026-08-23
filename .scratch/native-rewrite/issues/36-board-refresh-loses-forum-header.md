# 36 — P2:版块页下拉刷新之后「版头 + 子版块」整块消失,重进也不恢复

**Status:** open

**Severity:** P2(功能可用,但刷新一次就永久少掉两块内容:置顶版头入口与子版块 chip 行,
连带「更多 → 子版块」变成空态「这个版块没有子版块」——子版块订阅/屏蔽这条路整条断掉,
除非杀进程重开)

## 现象

`emulator-5554`,`com.chasel.ng2.n` debug = HEAD c6c1e27,所有者账号已登录,
版块 **fid=-7「网事杂谈」**(合集/大区):

1. 深链或宫格进 fid=-7 → 列表顶上有「版头」分组;「更多 → 子版块」列得出 20+ 个子版块
   (网络游戏综合 / 游戏综合讨论 / 消费电子 IT新闻 / 篮球 / … 带「已订阅」「未知」「不可更改」三态)
2. 在列表顶部下拉刷新一次
3. 回到列表:**「版头」分组没了**,第一行直接是普通主题
4.「更多 → 子版块」:**空态「这个版块没有子版块」**
5. **退出这一屏再重进(甚至走 `am start` 深链重新进版块)照样是空的** —— 只有杀进程才恢复

第 5 步是这张票值得单开的原因:它不是「刷新那一下没画出来」,是**坏状态被留在了内存里**。

## 定位(未改代码)

两块内容同源,都挂在 `__F` 上,所以是一起消失的:

- `ui/board/BoardScreen.kt:132-134`
  `val headTid = firstPage?.board?.toppedTopicId`(`__F.topped_topic`)、
  `val subBoards = firstPage?.subBoards.orEmpty()`(`__F.sub_forums`);
  `:447-452` 两个 item 分别以 `headTid != null` / `subBoards.isNotEmpty()` 为条件。
- `ui/board/SubBoardsScreen.kt:87` 读的是**同一份** `state.pages.first().subBoards`,
  所以那一屏跟着变空。

坏状态留在内存的机制:`data/board/TopicListRepository.refresh(key)`(`:109-112`)
调 `fetchInto(key, page = 1, replace = true)`,`:154` 的
`val pages = if (replace) listOf(fetched) else state.pages + fetched`
把**整个 pages 换成刷新到的那一页**。这一页要是没有 `__F`,
之后 `ensureFirstPage` 看见 `pages` 非空就不再拉(`:87-95`),于是永远出不来。

## 是服务端不下发,还是解析丢了?——**没能坐实**,但两侧都做了排除

### 服务端侧(curl 对拍,游客态)

`POST https://bbs.nga.cn/thread.php?fid=<fid>&page=1&<format>`,
带 `X-User-Agent: Nga_Official` + 设备 WebView UA + `Referer: https://bbs.nga.cn/thread.php`:

| 请求 | 响应字节 | `__F` | `sub_forums` | `topped_topic` |
|---|---|---|---|---|
| fid=7 `__output=8` 第 1 次 | 15458 | ✅ | ✅ | ✅ |
| fid=7 `__output=8` 第 2 次 | 15458 | ✅ | ✅ | ✅ |
| fid=414 `__output=8` 第 1 次 | 40142 | ✅ | ✅ | ✅ |
| fid=414 `__output=8` 第 2 次 | 40142 | ✅ | ✅ | ✅ |
| fid=7 `__output=11` | 20399 | ✅ | ✅ | ✅ |
| fid=7 `lite=js` | 15535 | ✅ | ✅ | ✅ |

结论:**服务端不会「第二次就不下发 `__F`」**,三种格式也都带 `__F`
(而且都是 `data` 下的**具名键**,不是 `__output=11` 那种「真数组」位置量,
所以 `orderedEntries` 那条兼容路径不参与)。

**但现场那个 fid=-7 对不上这张表**:它是登录态才打得开的合集,
游客 curl 直接回 `{"error":{"0":"1:未登录", …}}`(403),复现不了。
用所有者的 cookie 去 curl 又越了走查纪律里「绝不打印/记录 cookie 值」的线,所以没做。

### 解析侧(读代码)

`core/api/TopicList.kt:296` 就是一句 `val forum = root["__F"] as? JsonObject`,
`:301` 是 `forum?.get("sub_forums") as? JsonObject`,**与格式无关、没有位置量、没有分支**。
清洗六步(`net/sanitize`)也不动 `__F`。单看代码找不到「解析会丢 `__F`」的路径。

### 还剩的两个嫌疑

1. **`thread.php` 的组合缓存是接口粒度共用一槽**(inventory §3 已列为已知风险:
   「`thread.php` 六个业务共用一槽」)。刷新那一发有没有可能拿到了别的业务
   (24h 热帖 / 搜索 / 某人的主题)喂进去的组合、打成了一个形状不同的请求?
   `rejectNonTopicList` 只否决「不是主题列表」,**一个没有 `__F` 的主题列表它是认的**。
2. **合集(fid < 0)在登录态下的 `__F` 行为**可能与普通版块不同。

## 怎么一次坐实(下一轮照这个做)

app 自己有现成的仪器 —— 实验室的「本次运行的组合」会把**最近若干个请求的
返回 data 键集**原样列出来,例如走查里抓到的初次加载那一发:

```
18:17:52 成功 thread.php?fid=-7&page=1 (1 次尝试) [format-rotation] json @ https://bbs.nga.cn
  → data{__CU,__GLOBAL,__ROWS,__T,__T__ROWS,__T__ROWS_PAGE,__R__ROWS_PAGE,__F} 37 条
```

**初次加载这一发是带 `__F` 的。** 所以只要:

1. 进 fid=-7 → 下拉刷新一次 → 立刻去「设置 → 高级 → 实验室与诊断 → 本次运行的组合」
2. 找到刷新那一发 `thread.php?fid=-7&page=1` 的 `data{…}` 键集

- 键集里**没有** `__F` → 服务端那一发就没给,app 只是把它照单全收了
  (那么修法是「`__F` 缺失时不要用新页覆盖掉旧页的 board/subBoards」,而不是去改解析);
- 键集里**有** `__F` → 解析或状态这一侧丢的,顺着 `parseTopicList` 往下查。

## 期望(不论上面哪一支)

**刷新不该把已经拿到的版块元信息弄丢。** `refresh` 用新页替换旧页时,
`board` / `subBoards` 这两项建议按「新的有就用新的,新的没有就留旧的」合并 ——
和分类树那边 `mergeBoardTree`「组成以服务端为准,单个字段服务端没给才回落缓存」
(`data/board/BoardTreeLoad.kt:63-73`)是同一条口径,现成的先例。

## 验收判据

1. 进 fid=-7(或任一带版头/子版块的版块)→ 记下「版头」在不在、「更多 → 子版块」的条数 N;
2. 下拉刷新一次 → 「版头」仍在、子版块仍是 N 条;
3. 退出重进版块 → 同上;
4. 顺带把实验室组合表里刷新那一发的 `data{…}` 键集贴进本票,把上面那个二选一钉死。

## 出处

票 18 D 段(2026-08-23)走查子版块订阅/屏蔽时撞到:屏蔽完「桌游讨论」回版块页下拉刷新,
再进子版块屏就空了。当时正被 [票 35](35-signed-in-cold-start-deadlock.md)(P0,登录态冷启动死锁)
挡住,没能继续查下去。
