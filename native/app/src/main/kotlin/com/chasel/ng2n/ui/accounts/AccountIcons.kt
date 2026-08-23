package com.chasel.ng2n.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

/**
 * 登录 / 账号管理 / 账号头这三处要用的图标。
 *
 * 票 12 是 Canvas 手画的;票 40 对形时发现「添加账号」的加号画在了人形**右**边,
 * 而 RN 的 `person_add` 加号在**左**边——现在直接取 RN 同一份字体的字形轮廓。
 * 尺寸保留票 12 定的那几档(与 RN 调用点的 `size` 一致)。
 */

@Composable
fun CloseIcon(tint: Color, size: Dp = 24.dp) = AppIcon(Ng2nIcon.CLOSE, tint, size)

@Composable
fun RefreshIcon(tint: Color, size: Dp = 22.dp) = AppIcon(Ng2nIcon.REFRESH, tint, size)

@Composable
fun LockIcon(tint: Color, size: Dp = 16.dp) = AppIcon(Ng2nIcon.LOCK, tint, size)

@Composable
fun ChevronIcon(tint: Color, pointsLeft: Boolean, size: Dp = 18.dp) =
  AppIcon(if (pointsLeft) Ng2nIcon.CHEVRON_LEFT else Ng2nIcon.CHEVRON_RIGHT, tint, size)

/** 单选圈:当前账号那一条是实心的。 */
@Composable
fun RadioIcon(tint: Color, checked: Boolean, size: Dp = 22.dp) =
  AppIcon(if (checked) Ng2nIcon.RADIO_BUTTON_CHECKED else Ng2nIcon.RADIO_BUTTON_UNCHECKED, tint, size)

@Composable
fun PersonAddIcon(tint: Color, size: Dp = 21.dp) = AppIcon(Ng2nIcon.PERSON_ADD, tint, size)

@Composable
fun LogoutIcon(tint: Color, size: Dp = 20.dp) = AppIcon(Ng2nIcon.LOGOUT, tint, size)
