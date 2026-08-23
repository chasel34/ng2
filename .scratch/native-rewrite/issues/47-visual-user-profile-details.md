# 47 — P3:用户资料屏缺斜纹底、冒号后无间距、状态行整行染绿

**Status:** resolved

**Severity:** P3(纯视觉)

## 现象

三条:

1. **头部块少了斜纹**。Expo 的青色头部块上有一层 45° 深青斜纹;原生是纯青平涂。
2. **标签与值之间没有间隔**。Expo 是「邮箱: N/A」「用户组: 学徒」「注册日期: 2011-03-16」,
   冒号后有明显间隔;原生是「邮箱:N/A」「用户组:学徒」,**全部挤在一起**。
   头部的「用户 ID: 10712430」也一样。六行信息 + 头部那行,七处全中。
3. **「状态」行整行染绿**。Expo 是「状态:」深色标签 + 「已激活」绿色值;
   原生连标签「状态:」一起是绿的。

其余对得上:顶栏、头部块高度、头像圆、用户名字号、「:: 基础信息 ::」橙色小标题、
卡片圆角与左右内距、六行的左右两列布局与行距(~36.5px 半缩放)。

## 对照图

[`../acceptance/visual/15-user.png`](../acceptance/visual/15-user.png)

## 期望

| | Expo(基准) | 原生(实际) |
|---|---|---|
| 头部底 | 青底 + 45° 深青斜纹 | 纯青 |
| 标签/值 | 冒号后有间隔 | 零间隔 |
| 状态行 | 标签用正文色,只有值是绿 | 整行绿 |

## 疑似代码位置

`ui/user/UserProfileScreen.kt`;RN 侧对应 `src/app/user/`。
斜纹那层在 RN 侧多半是一个重复背景/渐变,原生要用 `Canvas` 画或 `drawBehind` 铺。

## Comments

**2026-08-23 — 修复**(`ui/user/UserProfileScreen.kt`)

**1. 斜纹**——不是没画,是被 Compose 的约束夹没了。窄条原来写的是
`.offset(x, y = -118).width(12).height(354).rotate(45f)`,而 `Modifier.height` 会把尺寸
**夹进父级传下来的约束**;banner 只有 118 高,354 被压成 118。压完再绕自身中心转 45°,
整条落在 y<0 那一半,被 banner 的 `clipToBounds()` 裁得一根不剩 —— 于是「纯青平涂」。
改成 `requiredWidth` / `requiredHeight`(不吃父约束)后窄条真有 354 长,与 RN 的
`position:absolute; top:-118; height:354` 同义。数值(12 宽 / 34 间距 / 20 根 /
`primaryDark`)一个没改。

**2. 冒号**——RN 侧用的是**全角**`：`(`用户 ID：{uid}`、`{field.label}：`),原生写的是
半角 `:`。全角冒号自带间隔,这就是「冒号后有明显间隔」的全部来源。头部那行 + 九格
信息全部改成全角。

**3. 状态行整行绿**——RN 的一格是**两段**:外层 `gridCell` 是 `fg2`,内层只包住值那一段
才是 `field.color ?? fg`。原生把两段并成一个 `Text` 并整体染 `field.color ?: fg2`,
所以「状态:」跟着绿了,而且**其余八格的值也偏淡**(用了 fg2,RN 是 fg)。改成
`buildAnnotatedString` + 两段 `SpanStyle`:标签 `fg2`、值 `field.color ?: fg`。

**顺带收掉的重复**:这一屏本地又抄了一份 `AVATAR_COLORS` / `avatarColorFor` /
`avatarColorAt`(与 `ui/theme/Tokens.kt` 里那份逐字相同)。按票 48 的裁定「落在 token 层
更合适」,删掉本屏这份改为 import `theme.avatarColorFor`;`avatarColorAt` 挪进
`Tokens.kt`(`internal`,只给单测按档位对拍用)。`ui/user/UserScreenTextTest.kt` 的
import 跟着改,断言一字未动、仍然全绿。

**发现的票外问题**:`ui/topic/TopicPageBuilder.kt:158` 还有**第三份** `avatarColorFor`
(同样逐字相同)。没动 —— 那是票 13 的文件,属票外。建议后续一并指向 token 层。

**没做**:真机/模拟器复看(本轮不许上设备)。斜纹一项是按 Compose 约束语义推出来的
修法,建议复验时优先看 15-user 这一屏。
