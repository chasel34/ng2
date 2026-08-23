# 50 — P3:收藏夹顶栏图标拿错、空态间距偏大、滑杆轨道偏粗

**Status:** open

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
