# 25 — P2:「屏蔽此人」的撤销按钮不生效,规则留在盘上

**Status:** open

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
