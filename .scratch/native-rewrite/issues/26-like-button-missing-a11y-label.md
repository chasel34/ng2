# 26 — P3:楼层「点赞」钮没有无障碍标签,同排另外三个钮都有

**Status:** open

**Severity:** P3(无障碍缺陷;顺带让 uiautomator 驱动的自动化点不准这个钮)

## 现象

主题详情楼层底部动作排四个钮:**点赞 / 点踩 / 回复 / 楼层菜单**。
后三个都用 `IconAction(label = …)` 包了 `semantics { contentDescription = label }`,
无障碍树里分别是 `点踩`、`回复`、`楼层菜单`;
**点赞那个是就地写的 `Row`,没有任何 `contentDescription`**——树里是个 `clickable=true`
但 text 与 content-desc 都为空的节点,只有里面的赞数文本(如 `280`)能被读到。

TalkBack 上这个钮会被念成光秃秃的赞数,听不出是「点赞」。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. 进任意主题详情
2. `adb shell uiautomator dump`,找楼层动作排那四个节点

实测(第 1 楼那排):

```
(568, 1010, 726, 1136) click=true  text=''    content-desc=''      ← 点赞
(655, 1041, 713, 1104) click=false text='280' content-desc=''      ← 赞数
(739, 1020, 832, 1125) click=false text=''    content-desc='点踩'
(844, 1020, 931, 1125) click=false text=''    content-desc='回复'
(949, 1020, 1038, 1125) click=false text=''   content-desc='楼层菜单'
```

**期望**:点赞钮同样有 `contentDescription`(如「点赞」,或带上当前赞数)。
**实际**:空节点。

功能本身是好的:游客态点它确实出 Toast「登录后才能点赞点踩」(截图确认)。

## 疑似代码位置

`ui/topic/FloorCard.kt:290-305` —— 点赞钮为了把图标和赞数排在一个圆角背景里,
直接写了 `Row(Modifier.clip(...).clickable { onRecommend(floor, LIKE) })`,
绕开了同文件 `IconAction()`(`FloorCard.kt:327-338`,那里才挂 `semantics`)。
补一个 `.semantics { contentDescription = "点赞" }` 即可,注意别把里面赞数文本的
语义盖掉(需要的话用 `mergeDescendants`)。
