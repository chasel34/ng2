# 22 — P2:主题页顶栏「用网页版打开」跳系统浏览器,不进站内网页兜底屏

**Status:** resolved

**Severity:** P2(功能可用但走错屏;#20 `/web` 从主题页进不去)

## 现象

主题详情顶栏的地球钮(content-desc「用网页版打开」)点下去**离开 app**,在 Chrome 里打开
`bbs.nga.cn/read.php?tid=…&page=…`。同名按钮在版块页是**站内**的网页兜底屏。

## 复现(模拟器 emulator-5554,2026-08-22)

1. 进任意主题详情
2. 点顶栏地球钮
3. `uiautomator dump` 顶层窗口变成 `com.android.chrome`,url_bar = `bbs.nga.cn/read.php?tid=47406116&page=3&rand=26`

版块页对照:版块页顶栏同名按钮 → 站内 `WebFallbackScreen`(`ui/board/BoardScreen.kt:223`,
那里的注释就写着「站内网页兜底屏(票 17),**不开系统浏览器**」)。

## 定位

`ui/topic/TopicScreen.kt:220`

```kotlin
TopBarButton(onClick = { runCatching { uriHandler.openUri(webUrl) } }, label = "用网页版打开")
```

## 期望

与版块页一致,`nav.push(WebKey(webUrl, key.title))`。走站内 WebView 才留得住 app 自己的
cookie / UA(`inventory.md` §3:反封锁链链外第 6 步是 `/web` 页);跳出去等于把登录态
交给系统浏览器的 cookie 罐。

真要保留「用外部浏览器打开」,它该是另一条菜单项、另一个文案,而不是顶掉网页兜底的入口。

## 相关

票 21(失败面板上的同名按钮是空实现)。

## Comments

**修复(2026-08-23)**

`TopicScreen.kt` 顶栏地球钮从 `uriHandler.openUri(webUrl)` 改成
`nav.push(webKey)`,与版块页同一条路。`webKey` 由 `topicWebKey()` 造
(见票 21 Comments),`remember` 的键带上 `settings.host` / `vm.page` /
`currentModel?.subject`,翻页后再点开的就是当前这一页。

`uriHandler` 没有删:楼层正文里的外链(`FloorActions.onOpenLink`)本来就该跳系统浏览器,
那是别人的站。改掉的只是**本站自己那一页**。

**没做「用外部浏览器打开」这条菜单项**:票里说「真要保留它,该是另一条菜单项、另一个文案」
—— 那是加功能,不是修缺陷,留给主控决定要不要开票。

**单测**:与票 21 共用 `TopicNavKeysTest` 的 4 例(同一个 `topicWebKey`)。

**未做 / 待所有者**:模拟器被另一个代理占用,`uiautomator dump` 复验没做。

**发现的票外问题**:无。

**主控验收(2026-08-23)**:合并后重打包装模拟器复验通过(顶栏钮进站内 web 屏,topResumedActivity 仍是本 app)。
