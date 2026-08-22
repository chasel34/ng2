# 24 — P2:版块列表加载失败时把英文异常原文当用户文案

**Status:** open

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
