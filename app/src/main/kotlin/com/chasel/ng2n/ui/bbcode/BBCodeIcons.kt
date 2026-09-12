package com.chasel.ng2n.ui.bbcode

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

@Composable
fun ArticleIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.ARTICLE, tint, size)

@Composable
fun WarningIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.WARNING, tint, size)

@Composable
fun ImageIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.IMAGE, tint, size)

@Composable
fun DownloadIcon(tint: Color, size: Dp = 16.dp) = AppIcon(Ng2nIcon.DOWNLOAD, tint, size)

@Composable
fun PlayIcon(tint: Color, size: Dp = 20.dp) = AppIcon(Ng2nIcon.OPEN_IN_BROWSER, tint, size)

@Composable
fun ExternalIcon(tint: Color, size: Dp = 16.dp) = AppIcon(Ng2nIcon.NORTH_EAST, tint, size)

@Composable
fun FireIcon(tint: Color, size: Dp = 18.dp) = AppIcon(Ng2nIcon.LOCAL_FIRE_DEPARTMENT, tint, size)

@Composable
fun ChainIcon(tint: Color, size: Dp = 15.dp) = AppIcon(Ng2nIcon.ACCOUNT_TREE, tint, size)

@Composable
fun ChevronIcon(tint: Color, expanded: Boolean, size: Dp = 20.dp) =
  AppIcon(if (expanded) Ng2nIcon.EXPAND_MORE else Ng2nIcon.CHEVRON_RIGHT, tint, size)
