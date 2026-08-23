# 26 — P3:楼层「点赞」钮没有无障碍标签,同排另外三个钮都有

**Status:** resolved

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

## Comments

**完成摘要(B 段,2026-08-23)**

`ui/topic/FloorCard.kt` 的点赞钮补 `.semantics { contentDescription = "点赞" }`,
挂在那个就地写的 `Row` 上(`.clickable` 之后、`.padding` 之前),与同排另外三个钮
经 `IconAction(label = …)` 得到的语义一致。无障碍树里那个 `click=true` 的空节点
现在是 `content-desc='点赞'`。

**关键决定**

- **不用 `mergeDescendants = true`**。票里给的是「补 contentDescription 即可,需要的话
  用 mergeDescendants」,也允许「带上当前赞数」。合并会把里面那条赞数 `Text`(如 `280`)
  从无障碍树里吃掉 —— 票 26 自己的 uiautomator dump 就把它当独立节点列着,
  票 18 那类自动化走查可能在用。所以选**不合并**:父节点念「点赞」,子节点仍然念「280」,
  两条信息都在,现有的 dump 形状也只多一个 content-desc、不少任何节点。
- 赞数没有写进 contentDescription:它每次点赞都变,写进去等于把一个会变的值钉在
  一个语义标签里,而它本来就以文本形式读得到。

**没做 / 待所有者**

- Compose 的无障碍树只能在设备/instrumentation 上验,本票**没有开模拟器**(派活时明确要求),
  所以「dump 出来确实是 `content-desc='点赞'`」这一条**没有实机证据**,靠代码对拍
  同文件 `IconAction()` 的写法。真机走查时顺手 `uiautomator dump` 复看一眼即可。
- 纯 Compose 语义,没有可在 JVM 单测里钉的纯函数,本票不补单测。

**票外发现**

- 无。(同族的账号管理返回钮在票 32 一并收了。)
