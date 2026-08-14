# thread.php「成功但空」根因分析（只读，未改任何代码）

2026-08-13 · 分析范围：`src/core/net/**`、`src/core/api/topic-list.ts`、`src/store/{nga-client,topic-list}.ts`、`src/app/board/[id].tsx`
方法：读代码 + `pnpm test`（1049 通过 / 14 跳过，基线绿）+ 一份临时 vitest 复现（跑完已删，代码见 §5.1）+ 反编译 okhttp-4.12.0 的 `BridgeInterceptor` 字节码。
没碰模拟器 / adb，也没对 NGA 发过真实请求（怕污染你正在跑的走查的限流状态）。

---

## 0. 一句话结论

**「成功但空」这个状态是代码允许存在的：`thread.php` 的响应只要能洗成一个 JSON 对象，就一定是"成功"，哪怕里面一条主题都没有、甚至根本不是主题列表的形状。**
而 `format-rotation` 会把**任何**"成功"的格式 × 域名组合按接口 key（`thread.php`，全局唯一一条）记进 `comboCache`，`comboCache` 建在 `src/store/nga-client.ts:47` 的模块作用域 —— **一旦某次请求轮换到一个"能解析但没有 `__T`"的组合，这个组合就被钉死，之后所有走 `thread.php` 的页面（版块列表 / 搜索 / 收藏夹 / 热帖 / 精华区 / 某人的主题）永远返回 0 条、永远 `error === null`，直到进程重启把 `comboCache` 清空。**

这条链路我用 vitest 端到端复现了（§5.1），包括「第 4 次被封一下 → 之后 `__output=8` 明明已经恢复却再也不会被尝试」这个不可逆特征。

**代码里没有任何等于 3 的常量**，所以"恰好第 4 个"这一半我的根因解释不了，只能解释"一旦翻转就永久翻转 + 重启复位"。触发第一次翻转的那次失败来自服务端/时序，需要补日志才能定死（§3）。

---

## 1. 数据流：NGA 返回 X → UI 画空态

```
transport(expo/fetch) ──→ attempt.ts:135 发请求，attempt.ts:143 GBK 解码
                     │
                     ├─ attempt.ts:163-165  parse(text) = parseNgaJson
                     │        └─ envelope.ts:29 sanitize → 46 顶层必须是对象 → 50 抽 error
                     │           ★ envelope.ts:65  data = 'data' in root ? root.data
                     │                                  : 'error' in root ? undefined
                     │                                  : root          ← 顶层被当成 data
                     │        成功 ⇒ { ok:true, result:{ root, data, via } }
                     │
                     ├─ format-rotation.ts:72-74 ★ 只要 ok 就 cache.remember(key, combo)
                     │                              key = interfaceKeyOf() = 'thread.php'
                     │
                     ├─ fetcher.ts:126-128  第一个 ok 的策略直接返回，链结束
                     │
                     ├─ topic-list.ts:323   ★ 只检查 isRecord(result.data)，不检查里面有什么
                     ├─ topic-list.ts:228   parseTopicList 永不抛：__T 缺失 ⇒ topics = []
                     │                      __ROWS 缺失 ⇒ totalRows = topics.length = 0
                     │
                     ├─ store/topic-list.ts:49  getNextPageParam：空页 ⇒ undefined，不再翻页
                     │
                     └─ app/board/[id].tsx:194  topics.length===0 && error!==null → 错误页
                        app/board/[id].tsx:201  topics.length===0（error===null）→ 「这个版块还没有主题」
```

三个"★"就是全部的必要条件：

| 位置 | 现在的行为 | 后果 |
|---|---|---|
| `src/core/net/envelope.ts:65` | 顶层既无 `data` 又无 `error` 时，**把整个顶层当 `data`** | 任何一个陌生的 JSON 对象（`{"result":"ok"}`、`{"time":1}`、别的接口的响应、mirror 域名的落地页 JSON）都变成"合法的空主题列表" |
| `src/core/api/topic-list.ts:323` | 只要 `data` 是 record 就往下解 | 结构完全不对也算成功 |
| `src/core/net/strategies/format-rotation.ts:72-74` | `ok` 就 `remember(key, combo)` | **把"能解析"误当成"这个组合是好的"，并且永久优先它** |

`parseTopicList` 自己的"绝不抛"是**刻意**的设计（topic-list.ts:224-227 的注释：被封时这一页是用户唯一能看到的东西），问题在于它上面**没有任何一层**再判"这响应到底是不是一页主题列表"。

### 1.1 被排除的怀疑方向（都读过了）

- **`web-fallback({placement:'primary'})` 抢在 `format-rotation` 前面接管 thread.php**：不会。`web-fallback.ts:74` 的**路径判断在档位判断之前**，非 `read.php` 一律 `unavailable`，`only` 档也不例外。`topic-cache` 同理（`topic-cache.ts:96`）。这两档对 `thread.php` 是纯粹的空转。
- **限流/错误信封被判成成功**：不会走到空态。`{"error":{"0":"…"}}` → `errors.ts:90` 抽出 → `envelope.ts:51` 抛 `kind:'server'` → `defaultRetryable` 为 false → `fetcher.ts:146` 当场抛给调用方 → **错误页**（还会把服务端原话显示出来）。
  - 命中假错误白名单（`没找到` / `没有符合条件的结果` / `完毕`…，`constants.ts:73`）时：不抛，但 `envelope.ts:65` 让 `data === undefined` → `topic-list.ts:324` 抛 `kind:'parse'` → 也是**错误页**，不是空态。
  - HTML 挑战页 / 空 body → `JSON.parse` 失败 → `kind:'parse'`（可重试）→ 继续轮换 → 全挂则**错误页**。
  - 结论：**限流本身不会直接产生空态**，空态一定是"拿到了一个能解析、但没有 `__T` 的 JSON 对象"。
- **`error` 是数组**（`{"error":["访问过快"]}`）：`is-record.ts:3` 把数组排除，`extractServerError` 返回 `null`，**既不算错误也不算假错误**；顶层有 `error` 键 ⇒ `data === undefined` ⇒ `topic-list.ts:324` 抛 parse。不是空态，但是个真实的判定漏洞（错误原文被丢掉，用户看到的是"响应里没有 data"）。已在 §5.1 第 3 个用例里钉住。
- **react-query 缓存空结果**：`_layout.tsx:29-38` 只设了 `retry:1` + `refetchOnWindowFocus:false`，`staleTime`/`gcTime` 全默认（0 / 5min）。空结果确实会进缓存，**但它不是起因**——同一进程内换个没打开过的 fid 也空，说明不是缓存命中。它只是**修好之后的余震**：即使组合恢复正常，5 分钟内回到刚才那个版块仍可能直接吃到缓存里的空页（`isPending` 为 false，不重取）。

---

## 2. 根因候选（按可能性排序）与判定实验

四个候选**共用**上面 §1 的三个"★"（那是"成功但空"的必要条件，已经确定无疑），区别只在**是谁产生了那个"能解析但没有 `__T`"的响应**。

### 候选 A（最可能）：`comboCache` 被一个"能解析但没有 `__T`"的组合毒化

链路：第 4 次请求时 `__output=8@bbs.nga.cn` 撞上一次瞬时失败（限流/HTML 挑战页/连接被掐）→ `format-rotation` 按 `combo.ts:89-116` 的顺序换到 `lite=js` 或 `__output=11` 或换域名 → 其中某个组合返回了一个**能解析但不是主题列表**的东西 → `format-rotation.ts:73` 记进缓存 → 此后每个 `thread.php` 都从它开局，**第一发就"成功"，永远轮不到第二个组合**，`__output=8` 恢复了也不会再被试。

**这条路我已经在 vitest 里完整跑通（§5.1 用例 1，通过）**，包括"缓存里记的是 `jsonLite`""`__output=8` 总共只被打了 4 次，之后再没被碰过"。

它对得上的现象：进程内状态 ✓；force-stop 复位 ✓（`nga-client.ts:47` 的模块级 `comboCache`）；连已经成功过的 -7 也一起空 ✓（key 是全局的 `'thread.php'`）；`read.php` 基本正常 ✓（它的 key 是 `'read.php'`，另一条缓存项，且详情页还有 `forgetSuccessfulCombo('read.php')` 这个人工复位入口，`app/topic/[tid].tsx:266`）；WebView 正常 ✓（完全另一套请求）。

**这个组合是哪一个，有两个子怀疑：**

- **A1 `jsonVerbose`（`__output=11`）从来没有被验证过。** `combo.ts:21` 把它放进 `DEFAULT_ROTATION_FORMATS` 的唯一依据是 `docs/research/mnga-report.md:354` 的一句"源码注释里还提到 `__output=11` 是详细 JSON"。全仓库**没有一份 `__output=11` 的 fixture，没有一个单测**（`grep jsonVerbose` 只命中 `constants.ts:48` 和 `combo.test.ts` 里一处纯枚举顺序断言）。而 `docs/API文档.md:457` 给 `thread.php` 标的格式是 **XML / lite=js**，`__output=8` 都属于"Android 对所有接口的主选"这条经验，`__output=11` 在 thread.php 上的行为**纯属未知**。只要它返回的顶层不是 `{data:{__T:…}}`（例如顶层直接就是数据、或者是 `{"result":…}` 这种），`envelope.ts:65` 就会把顶层当 data，稳稳产出"成功 0 条"。
- **A2 换域名后凭证被 OkHttp 悄悄丢掉，变成游客请求。** 见候选 C —— 这会让 `ngabbs.com` / `bbs.ngacn.cc` / `nga.178.com` / `nga.donews.com` 上的请求变成游客，服务端很可能回一个"有 `__F` 没 `__T`"的壳。

**判定实验（不用改代码，最便宜，先做这个）：**

1. **复现空态后，去搜索页搜任意关键词，或打开「我的收藏」/「24 小时热帖」。** 这三处走的都是同一个 `thread.php`（`core/api/search.ts:68`、`core/api/topic-favor.ts:88`、`core/api/hot-topics.ts:48`），因此**共用同一条 `comboCache` 记录**。
   - 搜索/收藏夹**也空** ⇒ 候选 A 基本坐实（毒化的是共享的接口 key）。
   - 搜索**正常** ⇒ **候选 A 被排除**（同一个 key 不可能一个空一个不空），转去看 B/D。
2. **空态那一屏上面还有没有子版块横条 / 版头入口？**（`app/board/[id].tsx:220-237`，它们只依赖 `__F`）
   - **有** ⇒ 服务端确实回了 `{"data":{"__F":…}}` 但没有 `__T`：这是"服务端软封 / 游客壳"，指向 A2 或 D。
   - **没有** ⇒ `data` 里连 `__F` 都没有，响应形状完全不对：指向 A1（或顶层被当 data）。
3. **设置 → 实验室 → 导出诊断日志**（`app/settings/lab.tsx:64`）。空态期间**一条 chain-failure 都没有**才符合"整条链自认为成功"。有记录的话把它贴出来，里面有每次尝试的格式 × 域名。

**vitest 判定实验（把 A 变成回归测试，§5.1 用例 1 可直接粘）。**

### 候选 B：服务端对"第三方客户端指纹"软封，直接回一个没有 `__T` 的壳

NGA 认出 `X-User-Agent: Nga_Official` + 格式参数这套指纹后，不报错、只给空壳。这同样落进 §1 的三个★ 变成空态。
但它**解释不了 force-stop 能复位**（除非软封是挂在一枚 session cookie 上——见候选 C 的 cookie jar，session cookie 确实随进程死掉）。而**症状 4（同 app 内 WebView 正常）反过来是它的反证**：WebView 和 `expo/fetch` **共用同一个 cookie 存储**（见候选 C），如果软封挂在 cookie 上，WebView 那次访问也该一起坏。

**判定实验**：空态期间，用 `ng2://web?url=https://bbs.nga.cn/thread.php?fid=414&__output=8` 让 **WebView 打同一个格式参数的 URL**。
- WebView 也拿到没有 `__T` 的 JSON ⇒ 服务端软封（候选 B），跟客户端组合无关。
- WebView 拿到完整数据 ⇒ 差别在客户端发出去的东西（组合 / 头 / 凭证）⇒ 回到 A 或 C。

### 候选 C：`Cookie` 头在 Android 上被 OkHttp 覆盖，跨域名轮换后静默变成游客（**已用字节码确认，独立于本 bug 也是真 bug**）

- `src/core/net/auth.ts:38` 把凭证拼成一个 `Cookie:` **请求头**。
- `expo/fetch` 的 Android 实现（`node_modules/expo/android/src/main/java/expo/modules/fetch/ExpoFetchModule.kt:26-50`）用的是 **RN 的 `OkHttpClientProvider` 单例客户端**，并在 `OnCreate` 里塞进 `JavaNetCookieJar(ForwardingCookieHandler)` —— 也就是 **WebView 的 `CookieManager`**；`NativeRequest.kt:41` 只有在 `credentials !== 'include'` 时才关掉 cookie jar，而默认就是 `include`。
- 反编译 `okhttp-4.12.0` 的 `BridgeInterceptor`（字节码 offset 184-232）：
  ```
  184: cookieJar.loadForRequest(url)
  204: isEmpty()  → 非空则
  221: ldc "Cookie"
  229: Request$Builder.header(String,String)   ← header() 是「替换」，不是 addHeader()
  ```
  对比同一方法里 `Host` / `Accept-Encoding` / `User-Agent` 都有 `Request.header(...) == null` 的守卫（offset 157/166/236），**唯独 `Cookie` 没有守卫、无条件覆盖**。

结论：**只要 cookie jar 里对该域名有任何一枚 cookie，我们手写的 `Cookie: ngaPassportUid=…; ngaPassportCid=…` 就会被整条替换掉。**
在 `bbs.nga.cn` 上通常无害（WebView 登录过，jar 里本来就有 passport cookie，所以现在能用）；但**换到 `ngabbs.com` / `bbs.ngacn.cc` / `nga.178.com` / `nga.donews.com` 时，jar 里没有 passport cookie，NGA 一旦在第一次响应里下发任意一枚自己的 cookie（`guestJs` 之类），从第二次起我们的凭证就被顶掉，请求静默降级成游客** —— 而且这个 jar 是进程内的（session cookie 不落盘），**force-stop 复位**。

这条既可能是 A2 的成因，也单独构成"链一旦换域名就丢登录态"的隐患。

**判定实验**：空态期间打开 `ng2://web?url=https://ngabbs.com/thread.php?fid=414`，看是不是"你好 lemon43"还是游客。更直接的做法是在 `attempt.ts` 的 `report()` 里临时把最终 `Cookie` 头打进诊断（需要改代码，留给修复阶段）。

### 候选 D：连接池/传输层耗尽 —— **排除**

`transport.ts:42` 一次性 `arrayBuffer()` 读完，没有未释放的 body；`fetcher.ts:154` 那个"只建一次 transport"也不构成泄漏。
但顺带确认了一个事实：**`renewTransport` 在设备上是空操作**。`nga-client.ts:40` 的 `createTransport()` 只是新建一个 JS 闭包，底下的 `OkHttpClient` 是 `ExpoFetchModule` 里 `by lazy` 的**模块级单例**（连接池 / dispatcher / cookie jar 全共享），`NativeRequest.kt:40` 的 `client.newBuilder().build()` 也共享同一个连接池。也就是说 **ADR-0002 写的"每次重试前重建 HTTP client"在 Android 上并没有真正发生**——不是本 bug 的成因，但 ADR 与现实已经对不上了。

---

## 3. 为什么恰好是第 4 次 —— 说明：我的根因解释不了这个数字

代码里**没有任何等于 3 的阈值**（`DEFAULT_MAX_ATTEMPTS = 6`；`retry: 1`；轮换组合上限 6）。我的根因解释的是**不可逆性**（第一次翻转之后永远不恢复，且只有重启能复位），触发第一次翻转的那次失败是**服务端行为或时序**，从代码里推不出来。

已知的"每次开版块打几发"影响这个数字：正常情况下一个版块 = **1 发** `thread.php`（`store/topic-list.ts:37` 的 page 1）；只有 `FlashList` 的 `onEndReached`（`board/[id].tsx:251-253`）在首屏就触发时才会追加第 2 页。失败时 react-query 的 `retry: 1` 会**再整链跑一遍**（一次失败最多 2 × 6 = 12 发 HTTP）。另外通知轮询（≥30s 一次 `nuke.php`）和「24 小时热帖」的 `Promise.allSettled` 并发 5~10 发（`hot-topics.ts:48`）都会吃掉同一份服务端配额。

**要定死"3"，还缺这些证据（都需要一次带日志的构建）：**

1. **成功路径也要落盘**：现在 `fetcher.ts:179` 只在 `chain-failure` 时调 `onDiagnostic`，**成功的 attempt 全部丢弃**。至少要记：这次用了哪个组合、试了第几个、`data` 顶层有哪些键、`__T` 有几条。没有这个，这类"静默降级"永远查不出来。
2. **失败响应的前 200 字符**：第 4 次那一发到底收到了什么（HTML 挑战页？`{"error":…}`？空 body？），决定了是限流还是别的。
3. **`comboCache` 的变更事件**：组合从 A 变成 B 的那一刻打一条日志，直接就能看到"第几次请求翻的车"。

在拿到 1~3 之前，"3 次"我只能给一个**假说**：NGA 对带 `X-User-Agent: Nga_Official` 的 `thread.php` 有一个很紧的短窗口配额（3~5 发/分钟量级），第 4 发撞上它触发第一次轮换；之后配额恢复了，但客户端已经被自己钉在坏组合上，所以**再也回不去**。这个假说和"重启复位"是一致的，但配额那部分我没有证据。

---

## 4. 修复方案

### 4.1 必须修

**M1. 「成功但 0 条主题」和「真的空版块」必须在数据层就分开**（这是你点名的那条）

改 `src/core/api/topic-list.ts:303-327`：`fetchTopicList` 里在 `parseTopicList` 之前先判断响应**是不是一页主题列表**。判据用结构键而不是条数：

```ts
// topic-list.ts:323 附近
if (!isRecord(result.data)) {
  throw new NgaError({ kind: 'parse', message: '主题列表响应里没有 data', via: result.via })
}
// __T 缺席 ≠ 空版块：空版块服务端照样给 __T:{} 和 __F。
// 一个结构键都没有 ⇒ 这不是 thread.php 的响应（被封 / 换错了格式 / 顶层被当成了 data）
const data = result.data
if (!('__T' in data) && !('__F' in data) && !('__ROWS' in data)) {
  throw new NgaError({
    kind: 'parse',
    message: '响应里没有主题列表结构（可能被限流或拦截）',
    via: result.via,
  })
}
```

`kind:'parse'` 是**可重试**的（`errors.ts:39`），所以它会让 `format-rotation` 继续换下一个组合，而不是把坏组合当成功记下来 —— **M1 顺带就把候选 A 的毒化闭环打断了**。
需要同步确认 `__T` 为空对象的真实响应长什么样（`search.ts` 的"没有符合条件的结果"走的是假错误白名单，另一条路）。fixture `thread-list-fid-7` / `forum-search-none` 可以拿来对照。
验证：§5.1 用例 1 应从"6 次全空"变成"抛错 → 错误页"；新增一个"`{"data":{"__T":{},"__F":{…},"__ROWS":0}}` 仍然是成功的空版块"的用例。

**M2. `envelope.ts:65` 的「顶层即 data」要收窄**

现在任何陌生 JSON 对象都会被当成 `data`。这个兜底只为 `app_api.php` 的版块分类树存在。最小改动：给 `parseNgaJson` 增一个可选的 `bare?: boolean`（或在 `NgaRequest` 上标 `envelope: 'wrapped' | 'bare'`），默认 **wrapped**——顶层没有 `data` 键就抛 `kind:'parse'`；只有 `board-tree` 那条调用显式传 `bare`。
影响面要扫一遍 `core/api/*` 里所有直接读 `result.data` 的地方（`grep -n "result.data" src/core/api`）。
验证：`envelope.test.ts` 补"顶层无 data 无 error 时默认抛 parse、bare 模式才当 data"。

**M3. `format-rotation` 不能把"能解析"等同于"这个组合是好的"**

`format-rotation.ts:72-74`。两步走，任选或都做：
- **(a) 让业务层能否决**：`NgaRequest` 上加一个可选的 `validate?: (envelope) => boolean`，`thread.php` 传"必须有 `__T`/`__F`/`__ROWS`"。验不过就当 `kind:'parse'` 继续轮换。这是 M1 的加强版（M1 在链外抛，链已经把坏组合记下了；M3 让链内就否决，坏组合根本进不了缓存）。
- **(b) 缓存加护栏**：`combo.ts:55` 的 `createComboCache` 加"连续 N 次命中缓存却拿到空业务数据就 `forget`"或一个 TTL（比如 10 分钟），让坏组合自己过期。**至少要有一条能自愈的路** —— 现在唯一的复位手段是杀进程。

**M4. 版块页要有复位入口，且空态文案要分档**

- `app/board/[id].tsx:207` 的「刷新」应该像详情页那样先 `forgetSuccessfulCombo('thread.php')`（对照 `app/topic/[tid].tsx:265-266`），否则用户点一百次刷新都还是从坏组合开局。同时 `queryClient.removeQueries` 掉这条 key，绕开 react-query 里缓存的空页。
- 文案分三档（现在只有两档）：
  1. `error !== null` → `LoadFailedNotice`（现状保留）；
  2. **服务端确实说了"这版块没主题"**（`__T` 存在且为空 / `__ROWS === 0`）→ 「这个版块还没有主题」；
  3. 其它任何"0 条" → **不能用同一句**。M1/M2 落地后这一档会变成 `error !== null` 走第 1 档，但保险起见 `EmptyState` 里也留一句可区分的兜底，例如「没能拿到这个版块的主题列表，可能被 NGA 限流了」+ 「用网页打开」「重试」两个动作。
  这需要 `TopicList` 带上信息，建议加 `readonly source: 'server' | 'unknown'` 或干脆 `readonly hasListStructure: boolean`，由 `parseTopicList` 填。

### 4.2 顺带加固

**H1. 凭证不要再依赖 `Cookie` 请求头（候选 C）。** 两条路：
- 改用 `authMode: 'form'`（`auth.ts:43`，MNGA 的做法，`access_uid`/`access_token` 放 POST body）——**不受 cookie jar 干扰**，改动最小，`fetcher.ts:169` 的默认值从 `'cookie'` 改成 `'form'` 即可（注意 `attempt.ts:91` 会拒绝 GET + form，本项目默认 POST，没问题）。
- 或者设备侧登录后把 passport cookie 主动写进 `CookieManager`，让 jar 自己带（要新增原生依赖，不推荐）。
验证：加一个单测钉住"`Cookie` 头存在时也要同时能走 form"；设备侧验证用 `nuke.php?__lib=ucp&__act=get` 看返回的是不是自己的 uid。

**H2. 成功路径的可观测性（没有它，这类 bug 只能靠猜）。** `fetcher.ts:175-182` 现在只有 `chain-failure` 落盘。建议：
- `strategy-success` 时也记一条精简日志（策略名、组合、`data` 顶层键、`__T` 条数）；
- `comboCache` 每次 `remember` 到**不同**组合时记一条；
- 实验室页加个「本次运行的组合表」只读视图（`comboCache` 现在没有枚举口，加一个 `entries()`）。

**H3. `jsonVerbose`（`__output=11`）在补上 fixture 之前从 `DEFAULT_ROTATION_FORMATS` 拿掉**（`combo.ts:21`）。它是全链路里唯一一个**从未见过真实响应**的格式档。抓一份 `thread.php` 的 `__output=11` 响应存进 `__fixtures__` 再放回去；`docs/API文档.md:457` 说 thread.php 的正经格式是 XML / lite=js，`__output=11` 属于"听说过"的档位。

**H4. `error` 是数组时也要当错误**（`errors.ts:99`）：加一条 `Array.isArray(error)` 分支，把元素里的字符串拼成 message。现在这种响应会退化成"响应里没有 data"，把服务端说的原话丢了。

**H5. `interfaceKeyOf` 对 `thread.php` 太粗？** 现在版块列表 / 搜索 / 收藏夹 / 热帖 / 某人主题共用一条缓存记录。按"被封的粒度是接口"这个原则，共用是对的（`combo.ts:32-43` 的注释站得住），**但要配合 M3 的自愈**，否则毒化面就是整个 app 的一半功能。至少把这一点写进 ADR-0002 的已知风险。

**H6. ADR-0002 与现实对齐**：`renewTransport` 在 Android/`expo/fetch` 上无效（候选 D）。要么在 ADR 里注明"这一条在 RN 上做不到"，要么去掉这个概念，别让后来人以为重试是换了新连接。

---

## 5. 现有单测的空洞

| 空洞 | 位置 | 说明 |
|---|---|---|
| **没有任何测试覆盖"链成功但业务数据为空"** | 全仓库 | `topic-list.service.test.ts:94` 只测了 `{"data":""}`（非 record）→ 报错；`data` 是 record 但没有 `__T` 的情况**一个用例都没有**，而这正是线上这条 bug |
| **`format-rotation` 的"成功"体从来不含业务数据** | `format-rotation.test.ts:9` `OK = '{"data":{"0":"ok"}}'` | 于是"成功 = 组合可用"这个隐含假设从没被质疑过；没有"坏组合进了缓存之后再也出不来"的用例 |
| **`comboCache` 没有毒化 / 自愈 / 复位的测试** | `combo.test.ts` 只测枚举顺序 | `forget` 只在"全组合失败"路径上被测到 |
| **`__output=11` 零覆盖** | `__fixtures__/` | 没有 fixture，`jsonVerbose` 只在 `combo.test.ts:64` 作为枚举顺序的道具出现过 |
| **`envelope.ts:65` 的"顶层即 data"分支没有反面用例** | `envelope.test.ts` | 只验证了它对 app_api 有效，没验证它对陌生对象的杀伤 |
| **`store/topic-list.ts` 无单测** | — | `getNextPageParam` 的"空页 = 到底了（或者被封了）"这句注释本身就写明了二义性，却没有测试也没有区分 |
| **`app/board/[id].tsx` 三档 body() 无测试** | — | 空态/错误态/全被屏蔽三条分支没有渲染测试（项目里似乎没有 RN 渲染测试基建，可只在数据层保证） |

### 5.1 可直接粘的测试骨架（我跑过，3 个用例全绿；跑完已把文件删掉）

放到 `src/core/net/combo-poison.test.ts`。**修好之前它证明 bug 存在；M1/M3 落地后，用例 1 的断言要反过来写（第 4 个版块应当抛错，第 5 个版块应当恢复正常）。**

```ts
import { describe, expect, it } from 'vitest'

import { fetchTopicList } from '../api/topic-list'
import { createComboCache } from './combo'
import { parseNgaJson } from './envelope'
import { createNgaFetcher } from './fetcher'
import { createFormatRotationStrategy } from './strategies/format-rotation'
import type { HttpRequest, HttpResponse, HttpTransport } from './transport'

const utf8 = (text: string) => new TextEncoder().encode(text)

const GOOD = utf8(
  '{"data":{"__T":{"0":{"tid":1,"subject":"标题","author":"a"}},' +
    '"__F":{"fid":650,"name":"原神"},"__ROWS":100,"__T__ROWS_PAGE":35},"time":1}',
)
/** 能解析但没有 __T —— 本次分析的关键形态 */
const NO_T = utf8('{"data":{"__F":{"fid":650,"name":"原神"},"__ROWS":0,"__T__ROWS_PAGE":35},"time":1}')
/** 顶层既无 data 也无 error：envelope.ts:65 会把顶层当 data */
const FOREIGN = utf8('{"result":"ok","time":1}')
const BLOCKED = utf8('<html>403</html>')

function formatOf(request: HttpRequest): string {
  const url = new URL(request.url)
  const lite = url.searchParams.get('lite')
  return lite === null ? `__output=${url.searchParams.get('__output')}` : `lite=${lite}`
}

function fetcherWith(respond: (format: string) => Uint8Array) {
  const seen: string[] = []
  const transport: HttpTransport = (request) => {
    const format = formatOf(request)
    seen.push(format)
    const body = respond(format)
    const html = body === BLOCKED
    return Promise.resolve<HttpResponse>({
      status: html ? 403 : 200,
      contentType: html ? 'text/html' : 'text/javascript; charset=UTF-8',
      body,
    })
  }
  const cache = createComboCache()
  return {
    seen,
    cache,
    fetchNga: createNgaFetcher({
      transport,
      comboCache: cache,
      host: 'https://bbs.nga.cn',
      strategies: [createFormatRotationStrategy()],
    }),
  }
}

describe('组合缓存被「成功但空」的组合毒化', () => {
  it('第 4 次被封一下 → 轮到 lite=js 拿到无 __T 的响应 → 之后所有版块永远空', async () => {
    // __output=8 只在第 4 次被封（模拟一次限流），之后立刻恢复正常
    let outputCalls = 0
    const { fetchNga, seen, cache } = fetcherWith((format) => {
      if (format === '__output=8') {
        outputCalls += 1
        return outputCalls === 4 ? BLOCKED : GOOD
      }
      return NO_T
    })
    const open = (fid: number) => fetchTopicList(fetchNga, { boardId: fid, kind: 'board', page: 1 })

    expect((await open(650)).topics).toHaveLength(1)
    expect((await open(321)).topics).toHaveLength(1)
    expect((await open(436)).topics).toHaveLength(1)

    // 第 4 个版块：__output=8 被封一次，轮换到 lite=js，「成功」但 0 条
    expect((await open(414)).topics).toEqual([])
    expect(cache.get('thread.php')?.format).toBe('jsonLite')

    // 之后每一个版块（包括第一个成功过的 650）都从坏组合开局，
    // 而 __output=8 早就不封了却再没被试过 —— 只有杀进程能复位
    for (const fid of [428, 481, 650]) expect((await open(fid)).topics).toEqual([])
    expect(seen.filter((f) => f === '__output=8')).toHaveLength(4)
  })

  it('顶层既无 data 也无 error 时整个顶层被当 data，照样是「成功 0 条」', async () => {
    expect(parseNgaJson('{"result":"ok","time":1}').data).toEqual({ result: 'ok', time: 1 })
    const { fetchNga } = fetcherWith(() => FOREIGN)
    expect((await fetchTopicList(fetchNga, { boardId: 650, kind: 'board', page: 1 })).topics).toEqual([])
  })

  it('error 是数组时既不算服务端错误也不算假错误（H4）', () => {
    const envelope = parseNgaJson('{"error":["您的访问速度过快"]}')
    expect(envelope.data).toBeUndefined()
    expect(envelope.fakeError).toBeUndefined()
  })
})
```

---

## 6. 建议的下一步顺序

1. **先做 §2 候选 A 的免费实验**（搜索页是否同时空 / 子版块横条在不在 / 诊断日志是否为空）——三分钟，能把候选空间砍掉一半。
2. **M1 + M4 的文案分档**先落地（低风险、独立可测），至少让"被限流"不再伪装成"空版块"。
3. **H2 的成功路径日志**跟着 M1 一起进去，下一轮验收就能直接看到"第几次翻的车、翻到哪个组合"，"3"这个数字也就有答案了。
4. **M2 / M3 / H1** 影响面较大，等 3 的日志回来再定。

---

# 7. 已实施的修复（2026-08-13）

`pnpm typecheck` 干净，`pnpm test` **1078 passed / 14 skipped**（改动前是 1049，新增 29 个用例）。
下面每条都写了**在设备上怎么确认它好了**。

## 7.1 逐条改动

### M1 「不是一页主题列表」在数据层就报错 · `src/core/api/topic-list.ts`

- 新增 `hasTopicListStructure(data)`：`__T` / `__F` / `__ROWS` 至少有一个才算「服务端确实按主题列表回了话」。
  **判据是结构键而不是条数**——空版块服务端照样下发 `__T:{}` 与 `__F`。
- `fetchTopicList` 在 `isRecord(data)` 之后多一道形状检查，不过就抛 `kind:'parse'`（可重试）。
- `TopicList` 新增 `listStructure: boolean`（`src/core/api/types.ts`）；服务端明说「2048:没有符合条件的结果」
  的那条路走新的 `serverEmptyTopicList()`，它的 `listStructure` 是 **true**（拿到了，只是空的）。

**设备验证**：正常版块照常出主题；被限流时不再显示「这个版块还没有主题」，而是错误页
（文案「响应内容解析不了 / 第三方客户端被拦是最常见的原因」）。

### M3(a) 反封锁链的一票否决 · `NgaRequest.validate`

- `src/core/net/types.ts` 加 `validate?: (envelope) => string | undefined`；
  `src/core/net/strategies/attempt.ts:163` 在解析成功后调用它，返回说明就当 `kind:'parse'`。
- `src/core/api/topic-list.ts` 导出 `TOPIC_LIST_REQUEST`（含 `validate: rejectNonTopicList`），
  **四个 `thread.php` 调用点全挂上**：版块列表、主题搜索（`search.ts`）、收藏夹主题（`topic-favor.ts`）、
  某人的主题/回复（`user-topics.ts`）。热帖/精华区走的就是 `fetchTopicList`，自动继承。
- 效果：形状不对的响应**根本进不了 `comboCache`**（`format-rotation.ts:72` 的 `remember` 只在真 ok 时执行），
  链会接着换下一个组合。假错误（翻到底了）显式放行，不会被误判成坏组合。

### M3(b) 成功组合缓存能自愈 · `src/core/net/combo.ts`

- `createComboCache({ ttlMs, now })`，默认 **10 分钟**保质期（`DEFAULT_COMBO_TTL_MS`）。过期即当没记过，从默认组合重新试探。
- `format-rotation.ts`：缓存里那个组合**当场失手就立刻 `forget`**，并发的同接口请求不会再从它开局。
- 新增 `entries()`，供实验室页展示（H2）。

**设备验证**：复现空态后**等 10 分钟再打开版块**应当自己恢复（以前只能杀进程）。

### M4 版块页三档文案 + 真正的重试 · `src/app/board/[id].tsx`、`src/store/topic-list.ts`

- 新增 `useRetryTopicList`：**先 `forgetSuccessfulCombo('thread.php')`**，再 `resetQueries`（丢掉数据并让本屏重新拉）。
  用 `resetQueries` 而不是 `removeQueries`：这条 query 正被本屏观察着，`remove` 是给没有观察者的 query 用的。
  空态与错误态的按钮全部改走它；**下拉刷新维持原样**（不清组合，免得每次下拉都多打一发试探）。
- 三档：
  1. `kind: 'parse' | 'unavailable'` 的错误 → 整屏 `LoadFailed`，**重试 / 用网页版打开 / 重新登录**三个出路
     （「用网页版打开」按当前域名拼 `thread.php?fid=…`，合集用 `stid=`）；
  2. 别的错误（断网、服务端明说理由）→ 维持原来的轻量 `LoadFailedNotice`；
  3. 0 条主题：`listStructure === false` → 「没能拿到这个版块的主题列表 / 多半是被论坛限流或拦下了」+ 重试；
     被屏蔽规则挡光 → 原文案；真空版块 → 「这个版块还没有主题」。

### H3 `jsonVerbose`（`__output=11`）退出默认轮换 · `src/core/net/combo.ts:21`

`DEFAULT_ROTATION_FORMATS` 现在是 `['json', 'jsonLite']`，注释里写清楚「没有任何真实响应样本，抓到 fixture 再放回来」。
`RESPONSE_FORMATS` 里的档位保留，调用方仍可显式指定。

### H4 `error` 是数组时也当错误 · `src/core/net/errors.ts`

`{"error":["您的访问速度过快"]}` 现在解析成 `kind:'server'` 并带原话。
**空数组 `[]` 仍然不算错误**——PHP 的空数组序列化出来就是它，和「这个字段没内容」分不开，宁可放行。

### H2 成功路径的可观测性

- `src/core/net/diagnostics.ts`：`FetchDiagnostic` 新增 `success?: FetchOutcomeSummary`
  （策略 / 格式 / 域名 / `data` 顶层键 / `__T`·`__R` 条数）+ `summarizeEnvelopeData()` + `formatOutcome()`。
  **只记结构信息**：有一条单测专门断言正文标题不会出现在日志里。凭证一如既往只记 uid，不记 cid。
- `src/core/net/fetcher.ts`：链成功时发 `chain-success` 事件并走同一个 `onDiagnostic`。
- `src/store/diagnostics.ts`：
  - **内存**的「本次运行」表 `readRunLog()`（最近 40 条，成功失败都记，进程死即清）；
  - 落 MMKV 的仍是精选：失败全留，成功只在**组合变了**或**试了不止一次**时留。
    ⚠️ 这里我**没有按字面「每次成功都落盘」**：`recordFetchDiagnostic` 每次都要
    读整份日志 → `JSON.parse` → `stringify` → 写回，是请求热路径上的同步 MMKV 写，
    你正在跑性能走查，全量落盘会污染读数。要看全量就看内存那张表（下条）。
- `src/store/nga-client.ts`：`successfulCombos()`。
- `src/app/settings/lab.tsx`：「存储与诊断」里新增一行 **「本次运行的组合」**，
  副标题直接显示 `thread.php: json @ https://bbs.nga.cn · read.php: …`，点它把
  【当前组合】+【最近 20 个请求（成功/失败、几次尝试、落点摘要）】分享出来。

### M2 收窄「顶层即 data」· `src/core/net/envelope.ts`

- `parseNgaJson(text, via, shape)`，`shape` 默认 `'wrapped'`：顶层既没有 `data` 也没有 `error` 就抛 `kind:'parse'`。
- `NgaRequest.envelope?: 'wrapped' | 'bare'` 透传（`attempt.ts:163`）。
- **全量扫描结果：真正的 bare 调用点是 0 个。** 我按 `grep -n "result.data" src/core/api` 逐个看了
  11 个调用点，全是 `nuke.php` / `thread.php` / `read.php` / `forum.php`，一律带 `data` 壳；
  而 `app_api.php` 版块分类树（一直被当成 bare 的那个反例）**响应其实也带 `data` 键**
  （`__fixtures__/home-category.gbk.bin` 开头是 `{"other":{…},"data":{…}}`），
  且 `parseBoardTree` 读的是 `result.root` 而不是 `data`。
  所以**没有 bare 名单**；我只给 `fetchBoardTree` 显式标了 `envelope: 'bare'`——
  它确实读顶层，标出来是让这件事写在明面上，行为与改前完全一致。

### H1 凭证不再只靠 `Cookie` 头 · `src/core/net/auth.ts`

- 新增 `AuthMode: 'both'` 并设为默认（`fetcher.ts`）：**Cookie 头 + form 字段一起带**。
- 为什么不是直接换成 `'form'`（MNGA 的做法）：那会把**所有**接口的认证方式一次性换掉，
  而我没有设备验证的手段。`'both'` 是严格更安全的一档——原来能用 Cookie 的一个字都没少，
  只是多了一份顶不掉的 form 凭证。要不要收敛成纯 `form`，等你在设备上验一轮再定。
- `attempt.ts` 的 GET 守卫相应放宽：`both` 档下 GET 带不上 form 不算错（Cookie 头还在），
  只有显式 `auth: 'form'` 才报错。仓库里目前没有 GET 请求。

### ADR · `docs/adr/0002-anti-block-chain-first-class.md`

新增「现状修正」五条：成功判据不能只是「洗得成 JSON」；缓存必须能自愈 + **`thread.php` 的 key 是
半个 app 共用的**（H5）；**`renewTransport` 在 RN 上是空操作**（H6，附 `ExpoFetchModule` 的 `by lazy` 单例证据）；
凭证不能只放 Cookie 头（附 `BridgeInterceptor` 字节码结论）；可观测性是这条链的一部分。

## 7.2 测试（§5 那张空洞表逐行补齐）

| 空洞 | 现在 |
|---|---|
| 链成功但业务数据为空 | `src/core/net/combo-poison.test.ts`：一次瞬时失败后坏组合不进缓存、后续版块照常；全都拿不到列表时**报错**而不是空态 |
| `format-rotation` 的"成功"体不含业务数据 | 同上，成功体换成真实形状的主题列表，并断言 `cache.get('thread.php')` 记的是给出了列表的那个组合 |
| `comboCache` 毒化 / 自愈 / 复位 | `combo-poison.test.ts` 的「缓存过期后重新试探默认组合」（假时钟）+ `combo.test.ts` 的 TTL 与 `entries()` |
| `__output=11` 零覆盖 | 已退出默认轮换（H3），不再有未验证档位参与 |
| `envelope.ts` 顶层即 data 无反面用例 | `envelope.test.ts`：默认抛可重试 parse；显式 `bare` 才当 data；有壳时不受 `bare` 影响 |
| 真空版块 vs 没拿到 | `topic-list.test.ts` 的 `listStructure` 一组 + `topic-list.service.test.ts` 的两个新用例 |
| 成功路径无日志 | `diagnostics.test.ts`：成功也留记录（断言组合/键/条数），且**正文不进日志** |
| `error` 是数组 | `combo-poison.test.ts` |
| `both` 认证档 | `auth.test.ts` |

仍然没有的：`store/topic-list.ts` 那几个 hook 与 `app/board/[id].tsx` 的三档渲染**没有测试**——
仓库里没有 RN 渲染测试基建，这次不铺。判据都下沉到 `core` 有测试的地方了（`listStructure`、错误 kind），
UI 那层只剩三个 if。

## 7.3 你在设备上怎么确认（按这个顺序）

1. **组合表活了**：随便刷几屏 → 设置 → 实验室 → 「存储与诊断」里应当有一行
   **「本次运行的组合」**，副标题形如 `thread.php: json @ https://bbs.nga.cn · read.php: json @ …`。
   点它 → 分享面板里能看到【当前组合】和【最近 20 个请求】，每条带「成功/失败 · 几次尝试 · data{…} · N 条」。
   **这就是当初查这个 bug 最缺的东西。**
2. **复现原来的序列**：`am force-stop` 后照旧连开 650 → 321 → 436 → 414 → …
   - 全程正常 = 坏组合已经不会被记住；
   - 若第 4 个之后仍出问题，**它现在必须是错误页而不是空态**——顺手把「本次运行的组合」分享出来，
     里面直接写着第几发翻的车、翻到了哪个组合、那次拿回来的 `data` 有哪些键。§3 缺的那个证据就是它。
3. **空态文案分档**：找一个真的没帖子的版块 → 仍是「这个版块还没有主题」；
   被限流时 → 整屏错误页，底下有**重试 / 用网页版打开 / 重新登录**三个按钮。
4. **「重试」真的复位**：出问题时点「重试」——它会先清掉 `thread.php` 的组合缓存。
   以前点一百次都还是从同一个坏组合开局。
5. **自愈**：万一还是撞上，**等 10 分钟别动**，再开版块应当自己好（TTL 到期重新试探）。
6. **登录态**（H1）：切到设置里的镜像域名（`ngabbs.com`）再开版块与详情页，确认仍是登录态
   （能看到只有登录才可见的版块 / 详情页顶部没有游客提示）。这条是 `both` 认证档要验的。

## 7.4 交接给你的两件事

- **`src/app/history.tsx:44` 一度 typecheck 报错**（`onPress` 期望 `() => void`，实际 `(entry) => void`），
  是别的 agent 改到一半的中间态，几分钟后自己消失了。我没碰那个文件，现在 `pnpm typecheck` 是干净的，
  但如果你那边又看到这条，去问改 `store/history.ts` 的那位。
- **`pnpm lint` 在这个仓库跑不了，别顺手跑**：`package.json` 里有 `lint: expo lint`，但 eslint 从来没装过；
  跑它会**当场往 `package.json`/lockfile 里塞 eslint 依赖并生成 `eslint.config.js`**。我误跑了一次，
  已经 `git checkout package.json pnpm-lock.yaml` + 删掉生成的配置还原（`node_modules` 里多了 eslint 包，不影响构建）。
  要么把这个 script 删掉，要么真把 eslint 配起来。

- **`combo.ts` 的裸 NUL 是历史遗留，不是这次改动引入的**：`git cat-file blob HEAD:src/core/net/combo.ts`
  里就有一个（offset 2957，`comboId` 那行），一直可以追到 `6896174`「issue 18」那次提交。
  工作区现在是 0 个（你已修）。**但 `git diff` 会继续把它标成 Binary，直到这个修复被提交**——
  因为 diff 的另一侧（HEAD）仍然带着那个字节。提交之后就恢复正常。全仓再没有别的 NUL。

- **搜索页 / 收藏夹 / 热帖 / 精华区的空态文案还没分档**：数据层（`listStructure`）已经给了，
  但 `src/app/search.tsx`、`src/app/favorites/*`、`src/app/board/{hot,recommend}.tsx` 不在这次的改动范围里。
  它们现在的行为是：拿不到列表 → 抛错 → 各自的错误态（不会再假装成空列表），已经比改前正确，
  但那句「用网页版打开」的出路还没给。

---

# 8. 版块镜像行的负数版块 ID（2026-08-13，独立缺陷）

**现象**：消费电子版「小窗视界 [版面镜像]」点进去是错误页，服务端原话
`56:版面ID4286241377不存在`。

## 8.1 结论：你的推断成立，而且源头就在我们自己的解码器里

三条证据：

1. **算术对得上**：`4286241377 = 0xFF7ADA61`，按有符号 32 位读就是 **-8725919**。
2. **NGA 的版块 ID 确实可以是负数，而且不罕见**。`api/__fixtures__/thread-list-fid-7`
   这一份样本里：`__F.fid = -7`；主题行的 fid 有 `-7955747 / -608808 / -576177 / -343809 / -81981`；
   `sub_forums` 的 key 有 `-522474`（体育综合讨论）、`-1459709`（职场人生）。
   `-8725919` 正好是这一族（个人/特殊版面）。
3. **JSON 那条路没问题，坏的是 TLV 那条**。同一份样本里服务端在 JSON 里老老实实发负号
   （`"fid":-7`、key `-522474`），所以丢符号只可能发生在**我们自己**解 `topic_misc` 的地方——
   `src/core/local/title-style.ts` 的 TLV 解码把 4 字节大端读成了无符号：

   ```ts
   const value =
     ((bytes[at + 1] as number) << 24 >>> 0) +   // ← >>> 0 把高位符号抹掉了
     …
   ```

   `0xFF` 开头的 ID 于是变成 `2^32 - |x|`，`parseShortcut`（`topic-list.ts:78`）拿它去跳转，
   服务端当然说这个版面不存在。

**fixture 里没有直接的负数 sfid 样本**（6 条镜像行的 sfid 都是 835 / 510352 / 793 这种小正数），
所以这一条是「代码 + 算术 + 负数 fid 确实存在」三者合起来的判定，不是样本钉死的。
下次抓包如果能抓到一条负数镜像行，值得存成 fixture。

## 8.2 改了什么

- `src/core/local/title-style.ts`：新增 `signedBoardId()`，TLV 解码时 **`sfid` 按有符号还原**。
- **`stid` 与 `mask` 故意不动**——这是我对你那条指令做的一处收窄：
  - `mask` 是位字段，无符号读法才对（JS 的 `&` 本来也只按 int32 算，改不改结果一样，但语义上不该动）；
  - **`stid` 是主题 id 不是版块 id**（合集本身就是一个主题，`parseShortcut` 的合集分支用的是 `stid ?? tid`）。
    主题 id 是无上限的正整数，现在四千七百万量级；对它套「大于 2^31 就减 2^32」将来会把合法的大 tid
    平白改成负数。你原话是「只处理版块 ID 那几个字段（fid / sfid / stid）」，我把 `stid` 从这个名单里去掉了。
- `src/core/api/` 里所有读**版块 fid** 的地方一并收口（服务端万一在别处也发了无符号形态）：
  `topic-list.ts` 的 `parseTopic`（`fid`、`topic_misc_var[3]`）/ `parseParent`（`0`）/ `parseBoard`（`fid`）/
  `parseSubBoard`（非合集档的 id）、`board-tree.ts`、`board-favor.ts`、`search.ts` 的 `parseBoardSearch`。
  **`stid` 一律不过这条规则**，`filterId`（`sub_forums[3]`，订阅/屏蔽要原样回传给服务端的操作对象）也不动。

## 8.3 测试（+9 条，总计 1087 passed）

- `title-style.test.ts`：`sfid` 高位为 1 → `-8725919`；`stid` 不适用（仍是 4286241377）；
  `mask` 仍按无符号；`signedBoardId` 的边界（`2^31-1` 不动、`2^32` 及以上不动、`undefined` 不动）。
- `topic-list.test.ts`：镜像行两条来源（`topic_misc` TLV 与 `topic_misc_var`）都还原成 `-8725919`；
  正常小 fid（835）与服务端本来就发负号的（-522474）不受影响；
  **合集行 `tid = 4286241377` 原样保留**（钉住「没误伤 tid」）。

## 8.4 设备验证

消费电子版找到「小窗视界 [版面镜像]」那一行点进去：应当正常打开那个版块的主题列表，
而不是「56:版面ID…不存在」。顺带看一眼 fid=650 里那几条镜像行（剑斗绮谭 / 画外旅照 / 万文集舍 …）
仍然照常——它们是小正数 fid，走的是「不受影响」那条路。
