# 32 — P3:账号管理屏没接 app 的主题与顶栏,用的还是 Material3 默认皮

**Status:** open

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
