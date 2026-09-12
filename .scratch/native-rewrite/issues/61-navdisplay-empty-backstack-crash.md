# 61 — P1:NavDisplay backstack cannot be empty 崩溃(用户真机实发)

**Status:** open

**Severity:** P1(前台 FATAL,用户可复现路径未知)

## 现象

用户真机(pudding/25113PN0EC)logcat crash 缓冲区留存,2026-08-25 14:32:37,
release 包前台崩溃:

```
FATAL EXCEPTION: main  Process: com.chasel.ng2.n, PID: 23454
java.lang.IllegalArgumentException: NavDisplay backstack cannot be empty
  at androidx.navigation3.ui.NavDisplayKt__NavDisplayKt.NavDisplay(NavDisplay.kt:361)
  at ... RecomposeScopeImpl.compose → Recomposer.performRecompose(重组期间)
```

Suppressed 里带 `MotionDurationScaleImpl`、`StandaloneCoroutine{Cancelling}`,
即崩溃发生在某次含动画的重组/协程取消窗口内。

## 初步分析方向

- 全仓找 `NavDisplay` 的 backstack 来源:什么路径能把 backstack 清空后仍留在组合里
  (返回键连击?deeplink 重入?进程重建恢复时 savedState 里 backstack 为空?)。
- 2026-08-25 当天用户还遇到过登录态丢失(票 60,已修),时间上可能与进程重建相关:
  崩溃 → 系统重启 activity → savedState 恢复出空栈,值得优先排查。
- 修复方向通常是:backstack 永远保底一个根条目(pop 到最后一个时收敛为退出 app
  而不是清空),以及恢复路径上对空栈兜底。

## 复验

复现路径找到后补;至少要做「返回键快速连击到根 + deeplink 冷/温启动重入」两组走查。
