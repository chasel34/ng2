# 31 — P3:游客态点「清空全部通知」谎报成功

**Status:** open

**Severity:** P3(假反馈,不毁数据;但「说做了其实没做」这类话术不该出现在成品里)

## 现象

游客态进「我的被喷」,屏幕中央是「登录后才能收通知 / 去登录」,
顶栏的垃圾桶「清空全部通知」照常可点 —— 点下去弹提示条 **「已清空全部通知」**。
实际上什么都没发生:没登录,压根没有通知,`noti&__act=del` 那一发也没有出去。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. 抽屉 →「最近被喷」
2. 点右上角垃圾桶
3. 1 秒内截图(提示条 4s 自动消失)

**期望**:要么这个钮在游客态不出现 / 禁用,要么给「登录后才能…」。
**实际**:提示条「已清空全部通知」。截图 `N1.png`。

## 根因(已定位到行)

`data/notifications/NotificationPoller.kt:168-176`:

```kotlin
suspend fun clearAll() {
  val uid = currentAccountOf(accounts.accounts.first())?.uid ?: return   // ← 游客态在这儿悄悄返回
  clearNotificationFeed(client)
  …
}
```

它是**正常返回**而不是抛,于是 `ui/lists/NotificationsScreen.kt:121-124` 的

```kotlin
runCatching { deps.notifications.clearAll() }.fold(
  onSuccess = { Snackbars.show("已清空全部通知") },
  …
)
```

走了 `onSuccess`,报成功。

同文件 `markRead`(:180)是同样的 `?: return`,但它没有对应的成功话术,影响只是静默,不算这条。

## 同一族的另外两处

- **票 30**(收藏夹管理):`FavoriteFoldersScreen.kt:99` `val currentUid = uid ?: return` ——
  游客态点「创建」彻底静默,连假成功都没有。
- `TopicViewModel.kt:209` 也是 `?: return@launch`,那处是后台恢复阅读进度,没有 UI 承诺,不算缺陷。

建议一起收拾:**游客态的写入口统一走 `showLoginPrompt(nav, …)`**
(`ui/filters/FiltersScreen.kt:294` 已经是这个写法,拿它当范式)。
