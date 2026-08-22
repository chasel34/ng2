# 21 — P1:主题详情加载失败面板的「用网页版打开」「重新登录账号」是空实现

**Status:** open

**Severity:** P1(反封锁链第 6 步「用户手点网页兜底」在详情页整条断掉)

## 现象

某一页加载失败时,详情页画的 `LoadFailed` 面板给了三个出路——「重试」「用网页版打开」
「重新登录账号」。**后两个点了完全没反应**,只有「重试」是活的。

## 定位

`ui/topic/TopicScreen.kt:396-400`:

```kotlin
LoadFailed(
  error = state.error,
  onRetry = { vm.refresh(page) },
  onOpenWeb = { },      // ← 空
  onRelogin = { },      // ← 空
)
```

按钮本身画得好好的(`ui/topic/TopicChrome.kt:593`、`:597`),回调是空 lambda。

`core/net/NgaClient.kt:24` 写明「链外还有第 6 步『用网页版打开』(用户手点的一个路由)」,
`core/net/FetchDiagnostic.kt:153/158` 的兜底文案也在往这个按钮上引导
(「第三方客户端被拦是最常见的原因,可以先用网页版打开」)——**面板在教用户点一个死钮**。

## 期望

- `onOpenWeb` → `nav.push(WebKey(webUrlOf(key.tid, page, key.fav, settings.host), 标题))`
  (`TopicScreen.kt:143` 已经算好了 `webUrl`;`ui/board/BoardScreen.kt:223` 是同一件事的正确写法)
- `onRelogin` → `nav.push(Login)`

## 复现

代码即证据(空 lambda)。运行期复现:模拟器开飞行模式 → 进任意未缓存的主题 → 失败面板出现
→ 点「用网页版打开」/「重新登录账号」无任何反应。

## 相关

同屏顶栏那个同名按钮走的是另一条路(跳系统浏览器),见票 22。
