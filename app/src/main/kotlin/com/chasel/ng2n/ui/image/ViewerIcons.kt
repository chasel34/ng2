package com.chasel.ng2n.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

@Composable
fun BackIcon(tint: Color, size: Dp = 24.dp) = AppIcon(Ng2nIcon.ARROW_BACK, tint, size)

@Composable
fun SaveIcon(tint: Color, size: Dp = 23.dp) = AppIcon(Ng2nIcon.SAVE, tint, size)

@Composable
fun ShareIcon(tint: Color, size: Dp = 23.dp) = AppIcon(Ng2nIcon.SHARE, tint, size)

@Composable
fun MoreIcon(tint: Color, size: Dp = 22.dp) = AppIcon(Ng2nIcon.MORE_VERT, tint, size)
