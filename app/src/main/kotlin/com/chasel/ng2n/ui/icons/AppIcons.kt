package com.chasel.ng2n.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Ng2nIcon {
  MENU,
  SEARCH,
  ARROW_BACK,
  ARROW_FORWARD,
  MORE_VERT,
  ADD,
  CLOSE,
  CHEVRON_LEFT,
  CHEVRON_RIGHT,
  STAR,
  PUSH_PIN,
  CAMPAIGN,
  PERSON,
  PERSON_ADD,
  CHAT_BUBBLE,
  WORKSPACE_PREMIUM,
  LIBRARY_ADD,
  BOOKMARK,
  FOLDER_SPECIAL,
  WARNING,
  ARTICLE,
  REPLY,
  CACHED,
  SMS,
  NOTIFICATIONS_ACTIVE,
  SETTINGS,
  INFO,
  FILTER_ALT,
  REFRESH,
  ACCOUNT_TREE,
  LOCAL_FIRE_DEPARTMENT,
  CLOUD_OFF,

  SCIENCE,

  TEXT_FIELDS,
  BLOCK,
  CHECK_BOX,
  CHECK_BOX_OUTLINE_BLANK,
  EDIT,

  HISTORY,
  DELETE,
  DELETE_SWEEP,
  FOLDER,
  CREATE_NEW_FOLDER,
  EXPAND_MORE,
  RADIO_BUTTON_UNCHECKED,
  RADIO_BUTTON_CHECKED,
  DOWNLOAD,
  ALTERNATE_EMAIL,
  STICKY_NOTE_2,
  THUMB_UP,

  ANDROID,
  BATTERY_FULL,
  BUG_REPORT,
  CODE,
  CONTENT_COPY,
  DARK_MODE,
  DESCRIPTION,
  DEVICES,
  DRAW,
  FLAG,
  FOLDER_OPEN,
  GAVEL,
  IMAGE,
  KEYBOARD,
  LOCK,
  LOGOUT,
  LOW_PRIORITY,
  MORE_HORIZ,
  NORTH_EAST,
  OPEN_IN_BROWSER,
  PERSON_OFF,
  PHONE_IPHONE,
  PUBLIC,
  REMOVE,
  SAVE,
  SEND,
  SENTIMENT_SATISFIED,
  SHARE,
  SIGNAL_CELLULAR_ALT,
  SMARTPHONE,
  TAG,
  THUMB_DOWN,
  TUNE,
  UPDATE,
  WIFI,
  ;

  val glyphName: String get() = name.lowercase()
}

private val pathCache = arrayOfNulls<Path>(Ng2nIcon.entries.size)

private fun pathOf(icon: Ng2nIcon): Path {
  pathCache[icon.ordinal]?.let { return it }
  val data = ICON_PATHS[icon.glyphName]
    ?: error("图标 ${icon.glyphName} 没有路径数据:重跑 native/tools/gen_icon_paths.py")
  val path = PathParser().parsePathString(data).toPath()
  pathCache[icon.ordinal] = path
  return path
}

@Composable
fun AppIcon(
  icon: Ng2nIcon,
  tint: Color,
  size: Dp = 22.dp,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier.size(size)) { drawIcon(icon, tint) }
}

fun DrawScope.drawIcon(icon: Ng2nIcon, tint: Color) {
  val factor = size.width / ICON_VIEWPORT
  scale(factor, factor, pivot = Offset.Zero) { drawPath(pathOf(icon), tint) }
}
