# 37 — P2:主题详情的请求链跑在主线程上(`TopicRepository.loadDetail`)

**Status:** resolved

**Severity:** P2(不死锁、不崩,但**详情页每翻一页都在主线程上组装一次请求**:
取 UA、拼查询、发出去之前的准备、拿回来之后的解码与六步清洗,全在主线程。
表现是翻页那一下的首帧被白白拖住 —— 手感问题,不是功能问题)

## 出处

[票 35](35-signed-in-cold-start-deadlock.md)(P0,登录态冷启动死锁)的「发现的票外问题」:

> `ui/topic/TopicRepository.loadDetail` 是**同一类缺陷**:`fetchTopicDetail` 跑在调用方
> dispatcher 上,而 `TopicViewModel` 一律 `viewModelScope.launch`(= `Main.immediate`),
> 于是详情页每翻一页,请求链的前半段都在主线程上组装。本票没动它:它的单测用
> `advanceUntilIdle()` 驱动 `cacheTopicPages`,正确的修法是照着同文件里现成的
> `@ComputeDispatcher` 再注入一个 `@IoDispatcher`(生产 `Dispatchers.IO`、单测给测试调度器),
> 那是票 13 的地盘,建议单开一张。

票 35 把仓库层能摸到网络的入口一律加了 `withContext(Dispatchers.IO)`
(`BoardFavoriteRepository` / `TopicListRepository` / `HotTopicsRepository` / …),
**唯独 `ui/topic/TopicRepository` 没动** —— 它的单测靠 `advanceUntilIdle()` 驱动,
硬塞 `Dispatchers.IO` 会把活儿丢到真线程池上,虚拟时间管不着,`cacheTopicPages`
那一串限速用例会当场垮掉。所以要先有一个可注入的接缝。

## 现场

- `ui/topic/TopicRepository.loadDetail`:缓存没命中就直接 `fetchTopicDetail(client = client, …)`,
  **不切上下文**,跟着调用方走;
- 调用方全是主线程:
  - `ui/topic/TopicViewModel.kt:281` `deps.repository.loadPage(...)` ← `viewModelScope.launch`
    (`viewModelScope` = `Dispatchers.Main.immediate`),`loadPage` 里第一句就是 `loadDetail`;
  - `ui/topic/ChainViewModel.kt:110` `deps.repository.loadDetail(...)`,同样 `viewModelScope`;
  - `ui/topic/TopicViewModel.kt:688/700` `deps.repository.cacheTopicPages(...)`,同样 `viewModelScope`
    —— 「缓存本页」「缓存整帖」那一串 `read.php` 也在主线程上组装。
- `loadPage` 的**建模**那一半早就是对的:`withContext(compute)`(`@ComputeDispatcher`,
  生产 `Dispatchers.Default`)。缺的只是请求那一半的对称件。

与票 35 的关系:死锁本身已经与它无关(UA 那把 `lazy` 锁在票 35 里拆掉了),
剩下的是纯粹的「主线程上干了不该干的活」。

## 期望

照同文件里 `@ComputeDispatcher` 的做法,再注入一个 `@IoDispatcher`:

1. `TopicRepository` 多一个构造参数 `@IoDispatcher private val io: CoroutineDispatcher`,
   生产由 `TopicRepositoryModule` 提供 `Dispatchers.IO`;
2. 网络取值 `withContext(io)`;限速(`delay`)与进度(`downloadState`)**留在调用方上下文**,
   不跟着换线程;
3. 单测注入 `StandardTestDispatcher(testScheduler)` —— 与 `appScope()` 用的是同一个调度器,
   `advanceUntilIdle()` 照样推得动 `withContext(io)` 里的活。

## 验收判据

1. [x] `loadDetail` 的 `fetchTopicDetail` 在注入的 `io` 上跑,不在调用方上下文;
2. [x] 单测加一条断言:**transport 不在调用方线程上被调**(注入真的 `Dispatchers.IO`,
   判据才有意义);
3. [x] 票 13 原有的详情/回复链/整帖缓存单测一条不改地全绿(`advanceUntilIdle()` 驱动仍然有效);
4. [x] `cd native && ./gradlew :app:assembleDebug :app:testDebugUnitTest -q` 通过。

## Comments

### 2026-08-23 修复

`ui/topic/TopicRepository.kt`:

- 新增 qualifier `@IoDispatcher`(挨着现成的 `@ComputeDispatcher`),
  `TopicRepositoryModule.provideIoDispatcher()` 给 `Dispatchers.IO`;
- `TopicRepository` 多一个构造参数 `@IoDispatcher private val io: CoroutineDispatcher`;
- `loadDetail`:`fetchTopicDetail(...)` 整个包进 `withContext(io)`。缓存命中那条早退路径
  在 `withContext` **之前**,命中时一次线程切换都不发生;
- `cacheTopicPages`:那一发 `fetchTopicDetail` 也包进 `withContext(io)`(同一个文件、同一类
  缺陷、同样从 `viewModelScope` 调进来)。**`delay(intervalMs)` 与 `downloadState` 的更新
  留在调用方上下文** —— 限速的节拍与进度的次序不该跟着换线程,而且那是票 13 单测
  用虚拟时间钉住的东西。

`deferSnapshot` 那一支没动:它本来就 `scope.launch`(`@IoScope`,`Dispatchers.IO`)。

### 单测

- `app/src/test/kotlin/com/chasel/ng2n/data/RepositoryDispatcherTest.kt` 加 1 例
  `主题详情 loadDetail 切走`(判据 2):沿用票 35 的 `ThreadProbe`(假 transport 与假
  `UserAgents` 各记一次「我在哪条线程上被调的」),这里注入的是**真的 `Dispatchers.IO`**,
  断言 transport 与 UA 取值都不在调用方线程上。
  `ThreadProbe.client()` 顺带多收一个响应体参数 —— 详情页要一份 `read.php` 形状的信封,
  默认那份 `OK_JSON` 解不成 `TopicDetail`。
- `app/src/test/kotlin/com/chasel/ng2n/ui/topic/TopicFixtures.kt` / `TopicDepsFakes.kt`:
  两处造 `TopicRepository` 的地方补 `io`,默认值是
  `scope.testDispatcher()` —— 从传进来的 scope 上原样取它的调度器
  (单测给的是 `StandardTestDispatcher(testScheduler)`),`advanceUntilIdle()` 因此照样推得动。
  票 13 的 `TopicRepositoryTest` / `TopicViewModelTest` / `ChainViewModel` 相关用例
  **一行没改**,全绿(判据 3)。

**是真回归网**:把 `loadDetail` 里的 `withContext(io)` 换成 `run`(不切上下文)重跑,
`主题详情 loadDetail 切走` 立刻 FAILED(9 tests completed, 1 failed)。

`cd native && ./gradlew :app:assembleDebug :app:testDebugUnitTest -q` 通过:965 例、0 失败、
4 跳过(原有的两个联网冒烟,默认 `assumeTrue` 关着)。

### 有意偏离 / 决定

- **qualifier 放在 `ui/topic/TopicRepository.kt` 里**,和 `@ComputeDispatcher` 挨着,
  没有提到 `di/` 去。理由:票面点名「照同文件 `@ComputeDispatcher` 的做法」,
  而 `@ComputeDispatcher` 就住在这儿;`di/DataModule` 里现成的 `@IoScope` 是**scope**
  不是 dispatcher,两者语义不同,不合并。往后若有第二个仓库要用 `@IoDispatcher`,
  再搬到 `di/` 也不迟(搬家是纯改 import)。
- **`cacheTopicPages` 一并修了**,虽然票 35 的原话只点了 `loadDetail`。同一个文件、
  同一个调用方(`viewModelScope`)、同一类缺陷,分两次改反而要把同一段推理写两遍;
  分寸是「只加 `withContext`,限速与进度一行没动」。
- **测试侧的默认 `io` 从 scope 上取**,而不是各个用例显式传。这样票 13 那些
  `testRepository(client, appScope())` 的调用点一个字都不用改,也不会有人漏传一个
  `Dispatchers.IO` 把虚拟时间悄悄打穿。

### 未完成 / 待所有者

- **没有真机/模拟器复验**:本票开工时的约束是**不要用模拟器**。这条改动的收益是
  「翻页那一下主线程少干一段活」,值不值得量在**票 19 真机裁性能**时顺手看一眼
  (详情页翻页的 latch2present)。功能上没有行为变化,单测已覆盖。

### 发现的票外问题

- 无。票 35 起的那一轮「仓库入口下 IO」到此收口:`RepositoryDispatcherTest` 现在 9 例,
  覆盖版块收藏 / 主题列表 / 热帖 / 某人的主题 / 用户资料 / 搜索(两条)/ 主题详情。
