# 48 — P3:账号管理屏头像方块没用散列色,「添加账号」少了虚线边框

**Status:** open

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
