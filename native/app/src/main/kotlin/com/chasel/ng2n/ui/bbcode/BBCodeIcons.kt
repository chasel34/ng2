package com.chasel.ng2n.ui.bbcode

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

/**
 * 正文渲染器要的几个图标。
 *
 * 票 11 是 Canvas 手画的;票 40 起全部走 [AppIcon] ——轮廓取自 RN 侧同一份
 * Material 字体的同名字形(名字对着 `src/ui/bbcode/…tsx` 里那几处 `Icon name=`),
 * 尺寸保留原来那几档。
 */

/** `[collapse]` 折叠卡的提要图标(一页纸)。 */
@Composable
fun ArticleIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.ARTICLE, tint, size)

/** `[lessernuke]` 版规处罚提示的图标(感叹号三角)。 */
@Composable
fun WarningIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.WARNING, tint, size)

/** `[album]` 相册卡的图标(相框里一座山)。 */
@Composable
fun ImageIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.IMAGE, tint, size)

@Composable
fun DownloadIcon(tint: Color, size: Dp = 16.dp) = AppIcon(Ng2nIcon.DOWNLOAD, tint, size)

/** `[flash]` 媒体卡的图标(RN 侧 `media.tsx` 用的是 `open_in_browser`)。 */
@Composable
fun PlayIcon(tint: Color, size: Dp = 20.dp) = AppIcon(Ng2nIcon.OPEN_IN_BROWSER, tint, size)

/** 「点了会离开本 app」的角标(右上箭头)。 */
@Composable
fun ExternalIcon(tint: Color, size: Dp = 16.dp) = AppIcon(Ng2nIcon.NORTH_EAST, tint, size)

/** 热门回复区的图标(火苗)。 */
@Composable
fun FireIcon(tint: Color, size: Dp = 18.dp) = AppIcon(Ng2nIcon.LOCAL_FIRE_DEPARTMENT, tint, size)

/** 「查看对话链」入口的图标(一棵倒挂的树)。 */
@Composable
fun ChainIcon(tint: Color, size: Dp = 15.dp) = AppIcon(Ng2nIcon.ACCOUNT_TREE, tint, size)

/** 展开 / 收起的箭头。[expanded] 时朝下,收起时朝右。 */
@Composable
fun ChevronIcon(tint: Color, expanded: Boolean, size: Dp = 20.dp) =
  AppIcon(if (expanded) Ng2nIcon.EXPAND_MORE else Ng2nIcon.CHEVRON_RIGHT, tint, size)
