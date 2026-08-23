# 32 — P3:账号管理屏没接 app 的主题与顶栏,用的还是 Material3 默认皮

**Status:** resolved

**Severity:** P3(纯视觉 + 一个无障碍缺口;功能正常)

## 现象

`/accounts`(设置 →「账号管理」,或抽屉账号头)是**全 app 唯一一屏**没有走
`LocalNg2nColors` / `TopBar` 的:

1. **配色是 Material3 默认紫**。「添加账号」钮的图标与文字是淡紫色,
   而 app 的强调色是墨绿/青(`colors.primary`)。截图 `AC1.png` 里对比很明显 ——
   同一次进入,上一屏的设置页全是青色,进这屏突然变紫。
   切到「纯白」主题风格时会更扎眼(那一档是白底 + 深绿强调)。
2. **顶栏是就地拼的 `Row`**,不是别处统一用的 `TopBar` + `TopBarButton`,
   于是高度、标题字号、返回钮位置都和其它屏对不齐
   (标题基线 y≈161,其它屏是 171–235)。
3. **返回钮没有 `contentDescription`**:
   ```
   IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) { BackIcon(...) }
   ```
   无障碍树里是个空节点 —— 别的屏都是 `[返回]`。TalkBack 上念不出来,
   uiautomator 也定位不到(这一屏只能靠系统返回键退出)。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. 抽屉 → 设置 → 账号管理
2. `adb shell uiautomator dump` —— 整屏只有 5 个有名字的节点,**没有 `返回`**:
   ```
   '[ng2n-accounts-screen]' (0, 0, 1080, 2400)
   '账号管理' (143, 161, 314, 224)
   '[添加账号]' (32, 290, 1048, 416)
   '添加账号' (507, 327, 653, 380)
   '登录多个账号可减少跳转系统浏览器的概率;抽屉头部左右滑动即可快速切换当前账号。' (43, 463, 1037, 551)
   ```
3. 截图看「添加账号」的颜色

## 疑似代码位置

`ui/accounts/AccountsScreen.kt:84-96`(顶栏 + 返回钮)、以及整屏用的
`MaterialTheme.colorScheme.*` / `MaterialTheme.typography.*`。

这是票 15 明说的欠账 —— 票 15 Comments「关键决定」第 5 条:

> 图标 Canvas 手画…视觉用 Material3 默认配色,**不自造 token**(票 17 的活)。

票 17 铺完图标与 token 之后没有回头收这一屏。改动很小:
顶栏换成 `TopBar` + `TopBarButton(icon = ARROW_BACK, contentDescription = "返回", onClick = onBack)`,
颜色换 `LocalNg2nColors.current`。

## 顺带

同一族的无障碍缺口还有一个:楼层「点赞」钮也没有 `contentDescription`(票 26)。

## Comments

**完成摘要(B 段,2026-08-23)**

`ui/accounts/AccountsScreen.kt` 整屏接上全 app 那一套:

1. **顶栏**:就地拼的 `Row` + `IconButton` 换成 `TopBar(paddingHorizontal = 4.dp)` +
   `TopBarButton(icon = ARROW_BACK, size = 24.dp, box = 46.dp, contentDescription = "返回")` +
   `TopBarTitle(variant = SUB)` —— 与收藏夹管理、通知屏逐参数同款,高度(54 + 状态栏)、
   标题字号(`Typo.subTitle` 17/600)、返回钮位置这才和别处对得齐。
   顺带删掉屏上自己叠的 `windowInsetsPadding(WindowInsets.statusBars)`:`TopBar` 自己
   撑安全区(edge-to-edge),留着会叠两层。
2. **返回钮有名字了**:`TopBarButton` 同时挂 `onClickLabel` 与 `contentDescription`,
   无障碍树里是 `[返回]`,TalkBack 念得出、uiautomator 也点得到 —— 这一屏不再只能靠系统返回键退。
3. **配色**:`MaterialTheme.colorScheme.*` 全换 `LocalNg2nColors.current`。映射
   `background→bg`、`surface→surface`、`outlineVariant→divider`、`outline→meta`、
   `onSurfaceVariant→meta`、`error→danger`、`primary/primaryContainer` 同名。
   头像方块沿用全 app 既有搭配「`primaryContainer` 底 + `primary` 字」
   (对照 `FavoriteFoldersScreen` 的「默认」徽标),不再用 `onPrimaryContainer`。
4. **字号**:`MaterialTheme.typography.*` 全换 `Typo` —— 账号名 `listTitle`、
   副行 `listSubtitle`、头像缩写 `avatarInitial`、「添加账号」`drawerItem`(与 `PillButton` 同档)、
   底部说明 `note`。圆角换 `Radius.lg`(原来写死的 14.dp,值不变)、内距换 `Spacing.row`/`Spacing.xs`。
5. 退出钮的 M3 `IconButton` 换成与全 app 同款的圆形触控盒(`clickable(onClickLabel = …)`),
   涟漪不再取 M3 色;`contentDescription` 原样保留。

**关键决定**

- **只换皮,不改版式**:46 的头像、48 高的「添加账号」、10/18 的间隔、
  `nameAbbrev(name, 2)` 全部原值搬过来 —— 票 32 是 P3 视觉/无障碍缺陷,
  不是重排这一屏。`git diff` 里没有一个尺寸被顺手调过。
- `MaterialTheme` 这一屏彻底不用了,`IconButton` / `BackIcon` 两个 import 一并删。
  `BackIcon` 在 `ui/image/ImageViewerScreen.kt` 还在用(那一屏是纯黑看图态、
  故意不吃主题色),没动它。

**没做 / 待所有者(票外,已按简报写进这里而不是顺手改)**

- **`ui/accounts/AccountHeader.kt` 还是 Material3 默认色**(`colorScheme.primary` 当底、
  `onPrimary` 当字,`typography.titleMedium/labelMedium`)。它是抽屉顶部那块账号头
  (`ui/home/HomeScreen.kt:256` 挂上去的),同样是票 15 留下的欠账,同样会在抽屉里显紫。
  票 32 的口径是「账号管理**屏**」,而且 `HomeScreen.kt` 这轮有别的子代理在动,
  所以**没碰**。建议主控单开一张小票或并进后续走查。
- 没开模拟器(派活要求),所以「换主题跟着变」「dump 里有 `返回`」两条
  **没有实机证据**,靠与其它屏逐参数对拍。真机走查时值得复看一眼。
- 纯 Compose 视觉/语义,没有可在 JVM 单测里钉的纯函数;同文件的 `nameAbbrev`
  本票一个字没改(它眼下也没有单测,那是票 15 的地盘,不在本票范围内)。

**票外发现**

- 见上条(`AccountHeader.kt`)。

**主控验收(2026-08-23)**:账号管理屏有 app 顶栏(返回 / 标题)与同族配色;合并后构建通过。
