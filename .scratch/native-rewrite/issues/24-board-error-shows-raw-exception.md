# 24 — P2:版块列表加载失败时把英文异常原文当用户文案

**Status:** resolved

**Severity:** P2(文案事故;还顺带把域名轮换的内部状态漏给用户)

## 现象

版块列表加载失败,空态里印的是

> Unable to resolve host "bbs.ngacn.cc": No address associated with hostname

同一时刻同一台机器上,主题详情的失败面板印的是中文的
「连不上服务器 / 检查网络连接后重试」。**两条路的错误文案不是一套。**

另外 `bbs.ngacn.cc` 是域名轮换链里最后试的那个域名,不是用户在设置里选的那个 ——
把反封锁链的内部状态当错误信息给了用户。

## 复现(模拟器 emulator-5554,2026-08-22)

1. `adb shell cmd connectivity airplane-mode enable`
2. 首页 → 魔兽世界 → 点一个**没进过**的版块(实测「守望先锋」)
3. 空态出现,文案即上面那句英文;按钮只有「重试」

对照:同样飞行模式下进主题详情(未缓存的页),失败面板是中文文案 + 三个按钮。

## 定位

`ui/common/StateView.kt:198-202`

```kotlin
fun failureText(error: Throwable?): String = when {
  error == null -> "没能拿到数据"
  error is NgaError -> error.text
  else -> error.message ?: "没能拿到数据"   // ← 走到了这一支
}
```

走 `else` 说明版块那条路把 **原始 `UnknownHostException` 直接抛到了 UI**,没有像详情页
那样先过 `core/net` 的错误分类(`NgaErrorKind.NETWORK`)/`FetchDiagnostic` 的文案表。
`FetchDiagnostic.kt` 里现成的中文文案因此一句都没用上。

## 期望

- 版块(以及所有走同一仓库层的列表屏)的失败一律先分类成 `NgaError`,`failureText` 拿到
  的永远是已经翻译过的文案;
- `failureText` 的 `else` 分支不要把 `error.message` 直接印给用户,退化成「没能拿到数据」
  更安全(异常原文进诊断日志就够了);
- 文案里不要出现轮换到的域名。

## Comments

### 2026-08-23 修复

**根因修正**:票里推测「走到了 `else` 分支」——实际是 `error is NgaError -> error.text`
那一支。反封锁链在传输层失败时包的就是
`NgaError(NgaErrorKind.NETWORK, cause.message)`(`core/net/strategies/Attempt.kt:170-179`),
而 `cause` 是 okhttp 的 `UnknownHostException`,`message` 正是
`Unable to resolve host "bbs.ngacn.cc": ...`。所以**分类没问题,坏在文案取的是
`NgaError.text`(=传输层原文)而不是那张按 kind 分档的中文表**。
`else` 分支同样在漏(`error.message`),一并堵上。

**改了什么**(`ui/common/StateView.kt`):`failureText` 改成与主题详情失败面板
**同一张表** `core/net/describeFetchFailure(kind, status, message)`:

| 档 | 现在印的 |
|---|---|
| `network` | 连不上服务器(不含域名、不含异常原文) |
| `http` | 服务端返回 HTTP 403 |
| `parse` | 响应内容解析不了 |
| `server` | 论坛自己那句话(照搬,与主题详情同口径) |
| 其它 / 非 `NgaError` / null | 没能拿到数据(`FAILURE_FALLBACK`) |

异常原文只留在 `NgaError.cause` 与 `NgaError.diagnostic`(实验室页可导出),不上屏。
`failureText` 是全 app 的收口(空态、`LoadFailedNotice`、各屏 `Snackbars.show(failureText(...))`
都走它),所以「版块以及所有走同一仓库层的列表屏」一次改齐,不用逐个仓库补分类。

**有意偏离**:`LoadFailedNotice` 原来的 KDoc 写「文案取服务端/传输层给的那句话,
换成通用话术等于把排障线索丢掉」——这条对 `server` 档仍然成立(照搬原文),
但对 `network`/`http`/`parse` 不成立:那几档的 `message` 是给开发者看的,
排障线索的正经去处是诊断日志。KDoc 已改写。

**单测**:`ui/common/FailureTextTest.kt` 7 条,含现场那一发原样(断言输出里不含
`bbs.ngacn.cc` 与 `Unable to resolve host`)、与 `describeFetchFailure` 同表、
各档映射、以及「每一档都是中文且不含异常原文」的横扫。

**发现的票外问题**:同类漏法还有两处(本票没改,归主控排期):
`ui/topic/TopicViewModel.kt` 点赞失败的 `toast(cause.message ?: "操作失败,稍后再试")`、
`ui/topic/TopicRepository.kt` 缓存失败的 `cause.message ?: "缓存失败"`。

**主控验收(2026-08-23)**:合并后重打包装模拟器复验通过(飞行模式进版块 → 「连不上服务器」+重试)。
