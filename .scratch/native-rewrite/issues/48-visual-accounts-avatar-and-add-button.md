# 48 — P3:账号管理屏头像方块没用散列色,「添加账号」少了虚线边框

**Status:** resolved

**Severity:** P3(纯视觉;多账号时头像失去辨识度)

## 现象

票 32 已经把这一屏接上了 app 顶栏与主题,剩下两处:

1. **头像方块颜色**。Expo 是**橄榄绿底 + 白字**;原生是**淡青底(`primaryContainer`)+ 青字**,
   而且所有账号都是同一个色。
   RN 侧 `src/ui/avatar.tsx:17` 的注释写得很明白:
   > 按用户 key 稳定取一档占位底色……**账号管理页的账号头像也用它(同一 uid 到处同色)**。
   即 `avatarColorFor(uid)` 按 uid 弱散列从 `avatarColors` 里取一档。
   票 32 的修法选了「`primaryContainer` 底 + `primary` 字」(对照 `FavoriteFoldersScreen`
   的「默认」徽标),语义不同 —— 登了多个账号时原生这屏看不出人与人的区别。
2. **「添加账号」钮**。Expo 是**虚线描边**的空框;原生是实心浅底、**无描边**。

其余(账号卡的青色描边与圆角、当前账号的青色单选圈、红色退出钮、底部说明段落、
「添加账号」行高 62px 半缩放)两边同位同色。

## 对照图

[`../acceptance/visual/14-accounts.png`](../acceptance/visual/14-accounts.png)

## 期望

- 头像方块:按 uid 散列取色 + 白字,散列函数与色表照抄 `src/ui/avatar.tsx` 的
  `avatarColorFor` 与 `src/ui/tokens.ts` 的 `avatarColors`(逐字符 `hash*31 + code`,`% 0xffffff`)。
  这一份同时该被楼层头像占位复用 —— 落在 token 层比落在这一屏更合适。
- 「添加账号」:虚线描边空框。

## 顺带

「添加账号」的图标加号在人形右侧(Expo 在左),归票 40。

## 疑似代码位置

`ui/accounts/AccountsScreen.kt`(头像方块与添加钮)、`ui/theme/Tokens.kt`(补 `avatarColors` 色表)。

## Comments

**2026-08-23 — 修复**(`ui/accounts/AccountsScreen.kt`)

**1. 头像方块**:`primaryContainer` 底 + `primary` 字 → `avatarColorFor(account.uid)` 底 +
`colors.onPrimary`(白)字,与 RN 侧 `accounts.tsx:56` 的
`{ backgroundColor: avatarColorFor(account.uid) }` + `avatarText.color = onPrimary` 同源。
散列与色表**没有新写一份**:`ui/theme/Tokens.kt` 里早就有一份逐字照抄 TS 的
`avatarColorFor`(`hash = (hash*31 + Char.code) % 0xffffff`)与七档 `AvatarColors`,
这一屏直接 import 它 —— 也就是票里说的「落在 token 层比落在这一屏更合适」。

顺手把票 47 那一屏本地重复的那份删了(见票 47 Comments),现在 token 层这一份的使用者
是:楼层/列表头像占位(`ui/lists/ListCommon.kt`)、资料页 banner、账号管理。

**2. 「添加账号」**:`Modifier.border(1.5dp, colors.divider, …)` 实线 →
`drawBehind` 画一条带 `PathEffect.dashPathEffect(6dp on / 4dp off)` 的圆角虚线描边,
颜色改成 RN 用的 `colors.track`(原来错拿了 `divider`)。Compose 的 `Modifier.border`
只画实线,虚线只能自己画;描边整体内缩半个线宽,免得被外层 `clip` 削掉一半。
虚线的 6/4 节奏是实现时定的(RN 的 `borderStyle:'dashed'` 由平台决定节奏,没有可抄的
数值),**不进 token**:全 app 只有这一处虚线。

**没做**:真机/模拟器复看(本轮不许上设备);「添加账号」的加号在人形右侧那条归票 40,
未动。

**单测**:头像散列色本来就有两处覆盖(`ui/lists/ListScreenTextTest.kt`、
`ui/user/UserScreenTextTest.kt`),后者的 import 跟着改到 token 层,断言未动、仍全绿 ——
账号管理屏用的是同一个函数,不再另写重复用例。
