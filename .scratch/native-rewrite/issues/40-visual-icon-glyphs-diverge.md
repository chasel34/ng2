# 40 — P3:手画图标与 RN 图标集不同形,散布 10+ 处

**Status:** resolved

**Severity:** P3(纯视觉;每一处都不大,但铺满全 app)

## 现象

票 15 的关键决定是「图标 Canvas 手画」,票 17 铺了 token 但没有逐颗对形。逐屏对照下来,
下列图标与 RN 侧 `src/ui/icons.generated.ts` 的**意象或线条风格不一致**(不是抗锯齿差异,
是画的东西不一样):

| 屏 | 位置 | Expo | 原生 |
|---|---|---|---|
| 抽屉 | 清空我的收藏 | 实心三角警告 | 线框三角 |
| 抽屉 | 我的回复 | 实心折返箭头 | 线框折返箭头 |
| 抽屉 | 我的缓存 | 双箭头循环 | 单圈箭头 |
| 抽屉 | 设置 | 带齿齿轮 | 圆角无齿齿轮 |
| 抽屉 | 最近被喷 | 带响铃弧线的铃铛 | 无弧线铃铛 |
| 主题详情顶栏 | 网页版 | 经纬线地球 | 圆 + 十字线 |
| 主题详情页码条 | 跳页 | `⊂≡` | `≡` + 箭头 |
| 浏览历史 / 我的缓存 顶栏 | 清空 | 细线垃圾桶 + 左侧三短横 | 三短横 + 桶身带竖纹的重垃圾桶 |
| 已收藏的主题 顶栏 | 右上动作 | 文件夹 + **星** | 文件夹 + **加号**(11 屏两边都是 folder-plus,说明这一屏拿错了) |
| 我的被喷 空态 | 铃铛 | 带响铃弧线,宽 46px(半缩放) | 无弧线,宽 **36px**(小 22%;别的屏空态图标 45–48px 两边一致) |
| 账号管理 | 添加账号 | 加号在人形**左** | 加号在人形**右** |
| 看图屏 | 保存 | 软盘 | 下载箭头 |
| 网页版 | 「用 APP 阅读这一页」 | 手机(`src/app/web.tsx:130` 写死 `Icon name="smartphone"`) | 文档/列表 |
| 关于 | 数据来源 | `<>` | 树状图 |
| 关于 | 系统设置 | 齿轮带回退箭头 | 圆角齿轮 |
| 关于 | 诊断日志 | 虫子 | 锥形瓶 |
| 关于 | 免责声明 | 法槌 | 警告三角 |

## 对照图

`../acceptance/visual/` 下的 `02-drawer.png`、`04-topic.png`、`08-history.png`、
`09-caches.png`、`10-favorites.png`、`13-notifications.png`、`14-accounts.png`、
`19-about.png`、`20-image-viewer.png`、`21-web.png`

## 期望

以 `src/ui/icons.generated.ts` 里那一颗的路径为准逐颗对形(意象一致、线框/实心一致、
视觉尺寸一致)。**「已收藏的主题」右上那颗是拿错图标,不只是画得不像**,优先。

## 疑似代码位置

`ui/icons/AppIcons.kt`(公共集)以及各屏自带的图标文件:
`ui/accounts/AccountIcons.kt`、`ui/topic/TopicIcons.kt`、`ui/image/ViewerIcons.kt`、
`ui/bbcode/BBCodeIcons.kt`;「已收藏的主题」那颗在 `ui/lists/FavoritesScreen.kt` 的顶栏。

## 顺带

版块屏顶栏的「收藏本版块」星,Expo 是实心、原生是线框 —— 但两台的收藏态未必相同,
**没有并进本票**,复验时确认同态后再决定。

## Comments

**完成摘要**(2026-08-23)

图标不再手画:RN 侧那份 `assets/fonts/MaterialIconsOutlined-Regular.otf` 的字形轮廓,
按 24 格设计栅格导成路径常量,原生填充绘制 —— 与 RN 是**同一颗字形**,不是「画得像」。

- 新增 `native/tools/gen_icon_paths.py`(fontTools):读 `src/ui/icons.generated.ts` 的名字表 →
  取同名字形轮廓 → `x·24/512`、`24 − y·24/512` 翻成 y 向下的 24 视口 → 生成
  `ui/icons/IconPaths.generated.kt`(85 颗,29KB;84 颗来自 RN 的 `ICON_GLYPHS`,外加 `science`)。
- `ui/icons/AppIcons.kt` 改为路径填充(`PathParser` 解析 + 按 `边长/24` 等比缩放,解析结果按枚举序缓存)。
  `Ng2nIcon` 枚举 API 不变(旧成员一个没删,含已不再使用的 `SCIENCE`),新增 35 个成员补齐 RN 那张表;
  约定 **枚举名小写 = Material 图标名 = RN 的键**。`AppIcon()` 与 `DrawScope.drawIcon()` 签名不变。
- 各屏自带的四套手画图标(`ui/topic/TopicIcons.kt`、`ui/image/ViewerIcons.kt`、
  `ui/accounts/AccountIcons.kt`、`ui/bbcode/BBCodeIcons.kt`)函数签名与默认尺寸全保留,函数体改为委托
  `AppIcon`,逐个对上 RN 调用点的 `Icon name=`(地球 → `public`、跳页 → `low_priority`、
  保存 → `save`、发帖设备 → `android`/`phone_iphone`/`devices`、投票勾选 → `check_box*`/`radio_button_*`、
  `[flash]` → `open_in_browser`、外链角标 → `north_east`、对话链 → `account_tree` 等)。
- **拿错图标**的三处按 RN 源码改回:
  - 关于屏五行 `ACCOUNT_TREE/SETTINGS/SCIENCE/ARTICLE/WARNING` → `CODE/UPDATE/BUG_REPORT/DESCRIPTION/GAVEL`
    (RN `src/app/settings/about.tsx` 的 rows);
  - 网页版屏「用 APP 阅读这一页」`ARTICLE` → `SMARTPHONE`(RN `src/app/web.tsx:130`);
  - 「已收藏的主题」顶栏那颗**枚举本来就是** `FOLDER_SPECIAL`(与 RN `favorites/index.tsx:178` 一致),
    看着像 folder-plus 是手画版把 folder_special 画成了加号 —— 换字形后自动成为 folder + 星。
- 「我的被喷」空态铃铛偏小 22%:空态尺寸档两边本来就一样(都是 40),小是因为手画的铃铛
  只占了 0.36 个格。改成字形后与其它空态同尺寸,不需要单独调数值。

**单测**:`ui/icons/IconGlyphsTest.kt`,8 条 —— 每颗枚举都有路径、枚举与生成表**互为全集**、
枚举名小写 = 图标名、所有轮廓落在 24 格栅格内(半格容差)、轮廓量级不是被压扁的一条、
`arrow_back` / `add` 的完整路径钉死(挡住导出变换写反,比如漏了 y 翻转)、`ICON_VIEWPORT == 24`。

**关键决定**

- 选「导出路径常量」而不是「把 OTF 打进包用 Text 画」:字体加载时序与字形回退是 RN 那套
  「等字体 ready 才放行首屏」方案的一部分,原生这边没有理由继承;路径是编译期常量,JVM 单测能钉。
  代价是 29KB 源码而不是 331KB 资源,且改图标要重跑脚本(脚本头写了跑法)。
- fontTools 用 `uv run --with fonttools` 跑,不进项目依赖。

**未验证 / 待所有者**

- 没上模拟器/真机(票面要求不上)。形状是否逐颗对得上,复验时看
  `02/04/08/09/10/13/14/19/20/21` 那几屏。
- 顺带那条(版块顶栏「收藏本版块」星 实心 vs 线框):星的字形是 `star`(实心),
  两版现在同一颗;若复验仍不同,那就是收藏态不同,不是图标问题。

**发现的票外问题**

- `ui/topic/TopicOverlays.kt` 的楼层菜单没有阴影(RN `ui/menu.tsx` 的面板带 `elevation2`),
  顶栏 kebab 那份有。本票没动。
