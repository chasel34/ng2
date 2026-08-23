# 25 — P2:「屏蔽此人」的撤销按钮不生效,规则留在盘上

**Status:** resolved

**Severity:** P2(误操作无法回退;设计稿把「带撤销」当成这个动作的安全网)

## 现象

主题详情楼层菜单 →「屏蔽此人」→ 底部提示条「已屏蔽 UID:xxx,其发言将折叠 / **撤销**」。
点「撤销」提示条当场消失(说明点击确实进去了),但**规则没有被删掉**:该用户的楼层
仍然折叠,屏蔽规则页里那条规则还在,DataStore 里也还在。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. 进任意主题详情第 2 页,点某楼右下角「楼层菜单」→「屏蔽此人」
2. 记下提示条里的 UID(实测 `UID:65011477`)
3. 点提示条右侧「撤销」(提示条随即消失)
4. 查盘:

```
adb -s emulator-5554 shell run-as com.chasel.ng2.n \
  cat /data/data/com.chasel.ng2.n/files/datastore/ng2n-settings.preferences_pb \
  | strings -n 20 | grep -o 'local:user[^]]*'
```

**实测**:`{"id":"local:user:uid:65011477",…}` 仍在列表里。
设置 →「屏蔽规则」页也能看到这条,楼层继续折叠。
**期望**:撤销后规则消失、楼层展开。

复现率 3/3(UID 9553166 / 60577443 / 65011477 各一次)。

## 已排除的怀疑

- **点击没进去**:点完 0.3s 截图,提示条已经不见 —— `TopicOverlays.kt:348` 的
  `clickable { onDismiss(); action() }` 里 `onDismiss()` 明显跑了。
- **按钮被 FAB 挡住**:撤销按钮 `[879,2016][1005,2142]` 与 FAB `[896,2080][1027,2211]`
  确有重叠,但改点重叠区之上的 `(942, 2040)` 结果一样。(重叠本身也该收拾一下。)
- **id 对不上**:`createFilterRule` 的 id 是确定式的
  `filterRuleId(LOCAL, kind, value)`(`core/local/Filters.kt:251`),
  `StoredRule ⇄ MatchRule` 往返带 id(`ui/topic/FilterBridge.kt:24/36`),
  `sanitizeFilterRules` 也不改 id(`data/settings/FilterRules.kt:131`)。
  纯函数层 `removeFilterRule` 按 id 过滤,看着是对的。

所以问题在 `TopicViewModel.blockAuthor`(`ui/topic/TopicViewModel.kt:457-480`)那个
`action` 闭包实际跑起来时的行为 —— 需要打日志确认 `viewModelScope.launch { … }` 有没有
真的执行到 `updateFilterRules`(第 471-476 行)。

## 顺带

提示条(`SNACK_BOTTOM = 92.dp`)与右下角 FAB 在横向上重叠;撤销这类右对齐的动作按钮
和 FAB 抢同一块地方,设计上也该错开。

## Comments

### 2026-08-23 修复

**找到一条能证明的机制,并有回归用例钉住**:撤销原来挂在 `viewModelScope` 上
(`TopicViewModel.blockAuthor` 里的 `action` 闭包)。这一屏的 ViewModel 一旦被 clear,
`viewModelScope` 就是**已取消**的 scope —— 往上面 `launch` **不抛也不跑**,静悄悄什么都
不发生,正好就是「提示条消失了、规则还在盘上」。而提示条本来就是按「发起它的页面退场
之后还活着,撤销得等得到」设计的(`ui/common/Snackbar.kt` 原话),两者直接打架。

证据:新加的用例 `票 25 撤销挂在 app scope 上 这一屏退场了也照样删得掉`,
把 `undoBlockAuthor` 换回 `viewModelScope` 立刻红(实测 `17 tests completed, 1 failed`),
换成 `deps.scope` 就绿。

**改了什么**

1. `ui/topic/TopicViewModel.kt`:`blockAuthor` 的落盘与撤销都改挂 `deps.scope`
   (全 app 一个的 IO scope,`TopicDeps` 里本来就有,`onCleared` 落阅读进度用的就是它);
   撤销从提示条里的匿名闭包提成具名入口 `undoBlockAuthor(ruleId)` —— 闭包里的逻辑
   没法单独回归,而「有没有真的落到盘上」正是本票的全部内容。
2. **顺带**(票里那条):提示条与右下角 FAB 的重叠是**少算了导航栏 inset**。
   FAB 让开了(`bottom = 24.dp + navBarInset`),提示条没让(`bottom = 92.dp` 写死),
   inset 一超过 18dp 两者就压在一起 —— 现场量到的 FAB 顶 2080 < 撤销底 2142 正是这个。
   `ui/topic/TopicOverlays.kt` 与 `ui/common/Snackbar.kt` 两个宿主都补上
   `WindowInsets.navigationBars` 的底部内距。
3. **对 RN 版的有意偏离**:带动作的提示条自动消失时长 4s → 8s(无动作那一档仍是 RN 侧的
   4s)。4 秒是「读完一句话」的时间,不是「读完 + 认出右边那枚小字 + 抬手点中」的时间;
   撤销是误操作的安全网,窗口关早了等于没有。**这一条也解释了复现记录**:
   `uiautomator dump` + 解析 + `input tap` 的往返稳定超过 4.22s,那三次「点了没反应」
   很可能点在**已经自动消失**的提示条上(0.3s 后截图当然是空的)——
   「已排除的怀疑」里那条「点击确实进去了」并不成立。

**单测**:`ui/topic/TopicViewModelTest.kt`
- 既有用例 `屏蔽此人 落一条本地规则并给撤销` 加了**查盘**断言(原来只查了折叠状态,
  而现场恰恰是「不折了但规则还在」这类差异 —— 只查折叠是查不出本票的);
- 新增 `票 25 撤销挂在 app scope 上 这一屏退场了也照样删得掉`(`store.clear()` 之后
  再点撤销,断言 DataStore 里那条规则消失)。

**未完成 / 待所有者**:真机/模拟器上按复现步骤走一遍(本票在另一个代理占着模拟器的情况下
做的,全程未用设备);注意验证时要在提示条**还在屏上**的时候点撤销。

**发现的票外问题**:app 里有**两个** Snackbar 宿主 —— 全局的
`ui/common/Snackbar.kt`(`Snackbars` 单例,跨屏活着)与主题屏私有的
`ui/topic/TopicOverlays.kt`(挂在 `TopicViewModel.snackbar` 上,随屏退场)。
两者同底距、同配色、同时长,能同时出现且互相盖住;主题屏的那一份还天然带着「撤销随屏
消失」的隐患。合并归主控排期,本票只把行为修对,没动结构。
