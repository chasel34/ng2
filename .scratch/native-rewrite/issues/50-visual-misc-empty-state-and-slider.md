# 50 — P3:收藏夹顶栏图标拿错、空态间距偏大、滑杆轨道偏粗

**Status:** resolved

**Severity:** P3(三条零散视觉差,合成一张票)

## 现象

1. **「已收藏的主题」顶栏右上图标拿错了**。Expo 是**文件夹 + 星**(收藏夹管理),
   原生是**文件夹 + 加号**。同一颗 folder-plus 在「收藏夹管理」屏(11)两边都对 ——
   说明是这一屏引错了图标,不是画得不像。
2. **「收藏夹管理」空态里,标题与说明段落的间距偏大**。
   Expo「还没有收藏夹,点右上角新建一个」到下面那段说明是 60px(半缩放)≈ 23dp;
   原生是 106px ≈ 40dp,多出约 17dp。
3. **「字体和头像大小」屏的滑杆,未填充轨道明显更粗**。Expo 是极细的浅色发丝线,
   原生是明显更粗的灰色轨道条。五根滑杆全中。滑杆的左右端点、当前值气泡的位置与配色、
   「−」「+」两端钮两边一致,只有轨道粗细不同。

## 对照图

[`../acceptance/visual/10-favorites.png`](../acceptance/visual/10-favorites.png)(#1)、
[`../acceptance/visual/11-folders.png`](../acceptance/visual/11-folders.png)(#2)、
[`../acceptance/visual/17-fontsize.png`](../acceptance/visual/17-fontsize.png)(#3)

## 期望 / 实际

| # | Expo(基准) | 原生(实际) |
|---|---|---|
| 1 | folder-star | folder-plus |
| 2 | 标题→说明 ≈ 23dp | ≈ 40dp |
| 3 | 未填充轨道发丝线 | 明显更粗的灰条 |

## 疑似代码位置

1. `ui/lists/FavoritesScreen.kt` 的顶栏 `TopBarButton(icon = ...)`
2. `ui/common/StateView.kt`(空态标题与附加说明之间的 `Spacing`)或
   `ui/lists/FavoriteFoldersScreen.kt` 的空态组装
3. `ui/settings/FontSizeScreen.kt` 的滑杆轨道厚度;RN 侧原件是 `src/ui/slider.tsx`

## 记录但不开票(留档,复验时可再看)

- 「收藏夹管理」说明段落原生每行 33 字、Expo 31 字(段落宽相同),疑似字号小半档
- 「字体和头像大小」预览卡正文,Expo 折 3 行、原生折 2 行,同上
- 设置行尾 chevron 原生略粗略高(1–2px 量级)

这三条都在测量噪声边缘,单看不足以判定。

## Comments

**2026-08-23 — 处理结果:1 已随票 40 修掉、2 已修、3 判不成立**

**#1 顶栏图标**——不用改,票 40 已经修掉了。`ui/lists/FavoritesScreen.kt:147` 引的一直
是 `Ng2nIcon.FOLDER_SPECIAL`(与 RN `favorites/index.tsx:178` 的 `folder_special` 同名),
问题出在票 40 之前那套「Canvas 手画几何近似」把 folder-special 和 create-new-folder
画成了同一个形状。票 40(commit `95ba926`,22:28)换成从
`MaterialIconsOutlined-Regular.otf` 导出的真字形之后,`IconPaths.generated.kt` 里
`folder_special` 的路径末段就是那颗星(`M13.08 14.06 12.38 17.02 15 15.47 …`)。
本票是 21:55 写的,截图早于票 40。

**#2 空态间距**——已修。`ui/common/StateView.kt` 新增 `StateVariant.INLINE_HEAD`
(上 60 / 下 20 / 左右 20),`ui/lists/FavoriteFoldersScreen.kt` 的空态改用它。
原来用的 `INLINE` 是上下各 56(为了和 `LoadFailedNotice` 对齐),而 RN 侧这一屏
**根本没走共用的 `EmptyState`**:`favorites/folders.tsx` 自己写了一份 `center`
(`paddingTop: 60` + `padding: 20`),说明段 `hint` 再加 `paddingVertical: 6`。
所以两边的差是 `56+6` vs `20+6` = **36dp**,不是票里估的 17dp ——
票里 60px/106px 两个测量值是对的,只是 px→dp 的换算系数用大了:
按顶部留白反推(Expo `12+60=72dp` 对 103px、原生 `12+56=68dp` 对 98px)是
1.435 px/dp,46px 的差就是 32dp,与代码算出来的 36dp 对得上。

**#3 滑杆轨道粗细——判不成立**。两边的轨道都是 3dp、都取 `colors.track`,
代码(`ui/settings/SettingsUi.kt` 的 `SLIDER_TRACK_HEIGHT` / RN `ui/slider.tsx` 的
`TRACK_HEIGHT`)与像素两头都对得上。逐像素采样 `17-fontsize.png` 第一根滑杆的
未填充段(Expo x=450 / 原生 x=1000,同一张图):

| | 完全着色的行 | 折算 |
|---|---|---|
| Expo | y 550–553(220,212,190) | ≈ 4.1 px |
| 原生 | y 504–508(217,209,187,含两行半透) | ≈ 3.8 px |

原生反而**略细**,差值在半个像素以内,肉眼上的「更粗」应该是缩略图上的错觉。
填充段(青色)同样量了一遍:Expo 4 行、原生 ≈3.8 行,同样一致。**没改代码**。

**没做**:真机/模拟器复看(本轮不许上设备)。#2 建议复验时看 11-folders。

---

## 主控复验(2026-08-23,`emulator-5554`,HEAD 8f07588)

**三条全部通过(#1 随票 40 修掉、#2 已修生效、#3 复验站在「判不成立」这一边)。**

**#1 「已收藏的主题」顶栏图标**:两边都是 **folder + 星**。修票的判断(手画版把
`folder_special` 画成了加号,换字形后自动对上)在设备上成立。
[`after/10.png`](../acceptance/visual/after/10.png)

**#2 空态标题→说明段落间距**:全分辨率的墨迹行带

| 带 | Expo | 原生 |
|---|---|---|
| 空态图标 | y 480–550 | **y 481–551** |
| 标题「还没有收藏夹,点右上角新建一个」 | y 612–645 | **y 612–644** |
| 说明段第一行 | y 734–763 | **y 730–760** |

标题底 → 说明顶:Expo **89px**、原生 **86px**,差 3px = 1.1dp(修前差约 92px)。
`StateVariant.INLINE_HEAD` 生效。[`after/11.png`](../acceptance/visual/after/11.png)

**#3 滑杆轨道粗细**:第一根滑杆,未填充侧 x=900 与填充侧 x=300 的竖向取样

| | 未填充轨道 | 填充轨道 |
|---|---|---|
| Expo | y 1032–1039,**8px**,`#dcd4be` | 8px,`#14796b` |
| 原生 | y 941–948,**8px**,`#dcd4be` | 8px,`#14796b` |

厚度与颜色**逐位相同**(8px = 3.05dp = `SLIDER_TRACK_HEIGHT` 3dp)。
票面「原生明显更粗」确认为缩略图错觉,**判不成立**成立,不需要改代码。
[`after/17.png`](../acceptance/visual/after/17.png)

**「记录但不开票」那三条**:说明段每行字数、预览卡折行、chevron 粗细 —— 复验一并看过,
都落在「原生 CJK 字形整体窄约 2%」这一条系统性差里(见票 42 复验),仍不开票。
