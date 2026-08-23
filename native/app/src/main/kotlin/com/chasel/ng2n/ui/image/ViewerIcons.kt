package com.chasel.ng2n.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

/**
 * 查看器顶栏的四个图标。
 *
 * 票 12 是 Canvas 手画的几何近似;票 40 逐颗对形时发现「保存」画的是**下载箭头**,
 * 而 RN 侧那一颗是 `save`(软盘)——不是画得不像,是画的不是同一个东西。
 * 现在四颗都走 [AppIcon],轮廓取自 RN 同一份 Material 字体的字形。
 * 尺寸保留票 12 定的那几档(与 RN 调用点的 `size` 一致)。
 */

@Composable
fun BackIcon(tint: Color, size: Dp = 24.dp) = AppIcon(Ng2nIcon.ARROW_BACK, tint, size)

@Composable
fun SaveIcon(tint: Color, size: Dp = 23.dp) = AppIcon(Ng2nIcon.SAVE, tint, size)

@Composable
fun ShareIcon(tint: Color, size: Dp = 23.dp) = AppIcon(Ng2nIcon.SHARE, tint, size)

@Composable
fun MoreIcon(tint: Color, size: Dp = 22.dp) = AppIcon(Ng2nIcon.MORE_VERT, tint, size)
