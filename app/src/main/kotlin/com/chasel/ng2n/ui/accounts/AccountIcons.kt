package com.chasel.ng2n.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

@Composable
fun CloseIcon(tint: Color, size: Dp = 24.dp) = AppIcon(Ng2nIcon.CLOSE, tint, size)

@Composable
fun RefreshIcon(tint: Color, size: Dp = 22.dp) = AppIcon(Ng2nIcon.REFRESH, tint, size)

@Composable
fun LockIcon(tint: Color, size: Dp = 16.dp) = AppIcon(Ng2nIcon.LOCK, tint, size)

@Composable
fun ChevronIcon(tint: Color, pointsLeft: Boolean, size: Dp = 18.dp) =
  AppIcon(if (pointsLeft) Ng2nIcon.CHEVRON_LEFT else Ng2nIcon.CHEVRON_RIGHT, tint, size)

@Composable
fun RadioIcon(tint: Color, checked: Boolean, size: Dp = 22.dp) =
  AppIcon(if (checked) Ng2nIcon.RADIO_BUTTON_CHECKED else Ng2nIcon.RADIO_BUTTON_UNCHECKED, tint, size)

@Composable
fun PersonAddIcon(tint: Color, size: Dp = 21.dp) = AppIcon(Ng2nIcon.PERSON_ADD, tint, size)

@Composable
fun LogoutIcon(tint: Color, size: Dp = 20.dp) = AppIcon(Ng2nIcon.LOGOUT, tint, size)
