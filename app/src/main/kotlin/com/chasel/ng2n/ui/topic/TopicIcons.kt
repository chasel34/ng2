package com.chasel.ng2n.ui.topic

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.common.MotionIcon
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

@Composable
fun BackArrowIcon(tint: Color, size: Dp = 24.dp) = AppIcon(Ng2nIcon.ARROW_BACK, tint, size)

@Composable
fun GlobeIcon(tint: Color, size: Dp = 22.dp) = AppIcon(Ng2nIcon.PUBLIC, tint, size)

@Composable
fun OverflowIcon(tint: Color, size: Dp = 22.dp) = AppIcon(Ng2nIcon.MORE_VERT, tint, size)

@Composable
fun FilterIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.FILTER_ALT, tint, size)

@Composable
fun CloseIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.CLOSE, tint, size)

@Composable
fun InfoIcon(tint: Color, size: Dp = 19.dp) = AppIcon(Ng2nIcon.INFO, tint, size)

@Composable
fun CloudOffIcon(tint: Color, size: Dp = 19.dp) = AppIcon(Ng2nIcon.CLOUD_OFF, tint, size)

@Composable
fun DownloadIcon(tint: Color, size: Dp = 19.dp) = AppIcon(Ng2nIcon.DOWNLOAD, tint, size)

@Composable
fun BookmarkIcon(tint: Color, size: Dp = 19.dp) = AppIcon(Ng2nIcon.BOOKMARK, tint, size)

@Composable
fun BookmarkAddedIcon(tint: Color, size: Dp = 18.dp) = AppIcon(Ng2nIcon.BOOKMARK_ADDED, tint, size)

@Composable
fun ThumbUpIcon(tint: Color, size: Dp = 19.dp) = AppIcon(Ng2nIcon.THUMB_UP, tint, size)

@Composable
fun ThumbDownIcon(tint: Color, size: Dp = 19.dp) = AppIcon(Ng2nIcon.THUMB_DOWN, tint, size)

@Composable
fun ReplyIcon(tint: Color, size: Dp = 20.dp) = AppIcon(Ng2nIcon.REPLY, tint, size)

@Composable
fun PlusIcon(tint: Color, size: Dp = 27.dp) = AppIcon(Ng2nIcon.ADD, tint, size)

@Composable
fun RefreshIcon(tint: Color, size: Dp = 19.dp) = AppIcon(Ng2nIcon.REFRESH, tint, size)

@Composable
fun BlockIcon(tint: Color, size: Dp = 17.dp) = AppIcon(Ng2nIcon.BLOCK, tint, size)

@Composable
fun EmptyArticleIcon(tint: Color, size: Dp = 40.dp) = AppIcon(Ng2nIcon.ARTICLE, tint, size)

@Composable
fun JumpIcon(tint: Color, size: Dp = 15.dp) = AppIcon(Ng2nIcon.LOW_PRIORITY, tint, size)

@Composable
fun AttachImageIcon(tint: Color, size: Dp = 18.dp) = AppIcon(Ng2nIcon.IMAGE, tint, size)

@Composable
fun CellularIcon(tint: Color, size: Dp = 18.dp) = AppIcon(Ng2nIcon.SIGNAL_CELLULAR_ALT, tint, size)

@Composable
fun ClientIcon(tint: Color, kind: ClientIconKind, size: Dp = 13.dp) = AppIcon(
  when (kind) {
    ClientIconKind.ANDROID -> Ng2nIcon.ANDROID
    ClientIconKind.IOS -> Ng2nIcon.PHONE_IPHONE
    ClientIconKind.OTHER -> Ng2nIcon.DEVICES
  },
  tint,
  size,
)

enum class ClientIconKind { ANDROID, IOS, OTHER }

@Composable
fun ChoiceIcon(tint: Color, multiple: Boolean, chosen: Boolean, size: Dp = 17.dp) = MotionIcon(
  when {
    multiple && chosen -> Ng2nIcon.CHECK_BOX
    multiple -> Ng2nIcon.CHECK_BOX_OUTLINE_BLANK
    chosen -> Ng2nIcon.RADIO_BUTTON_CHECKED
    else -> Ng2nIcon.RADIO_BUTTON_UNCHECKED
  },
  tint,
  size,
)
