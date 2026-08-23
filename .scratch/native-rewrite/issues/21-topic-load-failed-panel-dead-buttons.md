# 21 — P1:主题详情加载失败面板的「用网页版打开」「重新登录账号」是空实现

**Status:** resolved

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

## Comments

**修复(2026-08-23)**

`TopicScreen.kt` 里 `LoadFailed` 的两个空 lambda 接上了:

- `onOpenWeb` → `nav.push(topicWebKey(vm.key, page, vm.settings.host, vm.currentModel?.subject))`
  —— **失败的是哪一页就开哪一页**(用的是 `TopicPageView` 的 `page` 形参,不是 `vm.page`;
  相邻页在屏外也会画这块面板)。
- `onRelogin` → `nav.push(Login)`,与 `ui/board/BoardScreen.kt` 同一条路。

顺手把「造哪个键」收敛成一个函数 `topicWebKey(key, page, host, subject)`
(`TopicScreen.kt`,紧挨 `webUrlOf`):顶栏那颗地球钮(票 22)与这块面板上的同名按钮
说的是同一件事,两边各写一遍迟早会像票 22 那样一边进站内、一边跳系统浏览器。
标题优先用键上带的,没有再退到这一帖真正的标题。

**单测**:`TopicNavKeysTest`(新)4 例钉 `topicWebKey` —— 带 fav / 不带 fav、
标题回退、域名走设置里选的那个。按钮 `onClick` 本身要 Compose UI 测试才测得到,
不在 JVM 单测射程内,所以把可测的那一半(造键)提成纯函数。

**未做 / 待所有者**:模拟器被另一个代理占用,飞行模式下的运行期复验没做。

**发现的票外问题**:无。

**主控验收(2026-08-23)**:合并后重打包装模拟器复验通过(飞行模式开未缓存主题 → 失败面板「用网页版打开」进站内 ng2n-web-screen)。
