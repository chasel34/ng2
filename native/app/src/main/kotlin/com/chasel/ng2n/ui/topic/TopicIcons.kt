package com.chasel.ng2n.ui.topic

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

/**
 * 主题三屏要的图标。
 *
 * 票 11 / 12 立的规矩是 Canvas 手画几何近似;票 40 逐颗对形发现这里画错了两颗:
 * 顶栏「网页版」是**经纬线地球**(`public`)不是圆加十字,页码条「跳页」是
 * `low_priority`(⊂ 加三横)不是三横加箭头。现在全部走 [AppIcon] ——
 * 轮廓取自 RN 侧同一份 Material 字体的同名字形,尺寸保留原来那几档
 * (与 RN 调用点标的 `size` 一一对应)。
 */

@Composable
fun BackArrowIcon(tint: Color, size: Dp = 24.dp) = AppIcon(Ng2nIcon.ARROW_BACK, tint, size)

/** 「用网页版打开」(地球)。 */
@Composable
fun GlobeIcon(tint: Color, size: Dp = 22.dp) = AppIcon(Ng2nIcon.PUBLIC, tint, size)

/** 顶栏「更多」。 */
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

/** 空态(一页纸)。 */
@Composable
fun EmptyArticleIcon(tint: Color, size: Dp = 40.dp) = AppIcon(Ng2nIcon.ARTICLE, tint, size)

/** 跳页(设计稿 low_priority)。 */
@Composable
fun JumpIcon(tint: Color, size: Dp = 15.dp) = AppIcon(Ng2nIcon.LOW_PRIORITY, tint, size)

/** 附件折叠条的两枚:有网(相框)/ 计费网络(信号格)。 */
@Composable
fun AttachImageIcon(tint: Color, size: Dp = 18.dp) = AppIcon(Ng2nIcon.IMAGE, tint, size)

@Composable
fun CellularIcon(tint: Color, size: Dp = 18.dp) = AppIcon(Ng2nIcon.SIGNAL_CELLULAR_ALT, tint, size)

/** 发帖设备角标(安卓 / iPhone / 通用),RN 侧 `floor-card.tsx` 的 `CLIENT_ICONS`。 */
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

/** 单选 / 多选圆点方框(投票只读)。 */
@Composable
fun ChoiceIcon(tint: Color, multiple: Boolean, chosen: Boolean, size: Dp = 17.dp) = AppIcon(
  when {
    multiple && chosen -> Ng2nIcon.CHECK_BOX
    multiple -> Ng2nIcon.CHECK_BOX_OUTLINE_BLANK
    chosen -> Ng2nIcon.RADIO_BUTTON_CHECKED
    else -> Ng2nIcon.RADIO_BUTTON_UNCHECKED
  },
  tint,
  size,
)
