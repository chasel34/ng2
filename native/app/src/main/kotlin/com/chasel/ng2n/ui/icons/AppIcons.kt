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

/**
 * 铺屏用的图标集。
 *
 * **票 40 改法**:形状不再手画,而是取 RN 侧那颗字形的**轮廓本身**——
 * `assets/fonts/MaterialIconsOutlined-Regular.otf` 里同名同码点的字形,由
 * `native/tools/gen_icon_paths.py` 导成 24 视口的填充路径([ICON_PATHS]),
 * 这里按调用方给的边长等比放大后填充。
 *
 * 为什么不直接把字体打进包、像 RN 那样用 `Text` 画:字形回退与字体加载时序是 RN 侧
 * 「等字体 ready 才放行首屏」那套方案的一部分,原生这边不需要继承;路径常量是编译期
 * 就定死的东西,JVM 单测能逐颗钉住(`IconGlyphsTest`),也省掉 331KB 的字体资源。
 *
 * 票 15 / 17 的「Canvas 手画几何近似」到此为止——手画版与 RN 版**画的不是同一个东西**
 * (线框 vs 实心、有无响铃弧线、软盘 vs 下箭头……,票 40 列了 17 处),
 * 而调用点只认 [Ng2nIcon] 这个枚举,不认画法,所以换底不动调用点。
 *
 * 枚举名小写 = Material 图标名 = RN 侧 `src/ui/icons.generated.ts` 的键
 * ([Ng2nIcon.glyphName]),两版因此是同一张表:加图标 = 加枚举 + 重跑生成脚本。
 */
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

  /** 票 17 给「诊断日志」挑的锥形瓶。RN 侧那一行是 [BUG_REPORT](票 40 改回去),
   *  枚举留着不删:`Ng2nIcon` 是公共 API,别的分支上可能还在引。 */
  SCIENCE,

  // ---- 票 17b 追加(屏蔽规则 / 用户资料)----

  /** 关键词规则行(设计稿标的 `text_fields`) */
  TEXT_FIELDS,
  /** 「没有屏蔽项」空态与被拒回复行(设计稿标的 `block`) */
  BLOCK,
  /** 正则开关的勾选态(`check_box`) */
  CHECK_BOX,
  /** 正则开关的未选态(`check_box_outline_blank`) */
  CHECK_BOX_OUTLINE_BLANK,
  /** 签名卡右上角的「编辑」(`edit`) */
  EDIT,

  // ---- 票 17a 追加(搜索 / 收藏 / 历史 / 缓存 / 通知)----

  /** 历史页与搜索历史行 */
  HISTORY,
  /** 单条删除(缓存行尾、收藏夹卡片) */
  DELETE,
  /** 整屏清空(历史 / 缓存 / 通知顶栏) */
  DELETE_SWEEP,
  FOLDER,
  CREATE_NEW_FOLDER,
  /** 副标题条右侧那枚「点此换收藏夹」的箭头 */
  EXPAND_MORE,
  /** 搜索选项的单选圈(空) */
  RADIO_BUTTON_UNCHECKED,
  /** 搜索选项的单选圈(选中) */
  RADIO_BUTTON_CHECKED,
  /** 缓存行的下载标 */
  DOWNLOAD,
  /** 通知分组「@ 我的」 */
  ALTERNATE_EMAIL,
  /** 通知分组「给我贴条的」 */
  STICKY_NOTE_2,
  /** 通知分组「收到的评价」 */
  THUMB_UP,

  // ---- 票 40 追加:补齐 RN 侧 `ICON_GLYPHS` 的其余名字 ----
  // 主题三屏 / 看图屏 / 账号屏 / 正文渲染器原来各自手画一套(`ui/topic/TopicIcons.kt`
  // 等),票 40 把它们并到这张表上,所以这些名字要在。

  /** 楼层「发帖设备:安卓」 */
  ANDROID,
  BATTERY_FULL,
  /** 关于屏「诊断日志」(RN `src/app/settings/about.tsx`) */
  BUG_REPORT,
  /** 关于屏「数据来源」 */
  CODE,
  CONTENT_COPY,
  DARK_MODE,
  /** 关于屏「开源许可」 */
  DESCRIPTION,
  /** 楼层「发帖设备:其他」 */
  DEVICES,
  DRAW,
  FLAG,
  FOLDER_OPEN,
  /** 关于屏「免责声明」(法槌) */
  GAVEL,
  /** `[album]` 相册卡 / 楼层「点击显示图片」 */
  IMAGE,
  KEYBOARD,
  /** 登录屏密码框 */
  LOCK,
  /** 账号管理「退出登录」 */
  LOGOUT,
  /** 页码条「跳页」 */
  LOW_PRIORITY,
  MORE_HORIZ,
  /** 「点了会离开本 app」的角标 */
  NORTH_EAST,
  /** `[flash]` 媒体卡的播放钮 */
  OPEN_IN_BROWSER,
  PERSON_OFF,
  /** 楼层「发帖设备:iOS」 */
  PHONE_IPHONE,
  /** 主题详情顶栏「用网页版打开」(地球) */
  PUBLIC,
  REMOVE,
  /** 看图屏「保存」(软盘) */
  SAVE,
  SEND,
  SENTIMENT_SATISFIED,
  /** 看图屏「分享」 */
  SHARE,
  /** 楼层「省流量,点了才载图」 */
  SIGNAL_CELLULAR_ALT,
  /** 网页版屏「用 APP 阅读这一页」 */
  SMARTPHONE,
  TAG,
  /** 楼层「反对」 */
  THUMB_DOWN,
  TUNE,
  /** 关于屏「系统设置」 */
  UPDATE,
  WIFI,
  ;

  /**
   * 这一颗在 Material 图标集里的名字,也是 [ICON_PATHS] 与 RN 侧 `ICON_GLYPHS` 的键。
   *
   * `lowercase()` 不带 locale,土耳其语环境下也不会把 `I` 变成 `ı`。
   */
  val glyphName: String get() = name.lowercase()
}

/**
 * 解析好的路径按枚举序缓存。
 *
 * 竞态无所谓:两条线程同时解析同一颗,得到的是两个等价的 [Path],谁最后写进去都一样;
 * 画的时候只读。用数组而不是 map 是因为这东西在楼层卡片上每帧都要取(一张卡五颗)。
 */
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

/**
 * 画在当前 [DrawScope] 的**正方形**范围里(取 width 当边长)。
 *
 * 边长即字号:RN 侧 `Icon` 的 `fontSize` 就是图标边长,em 框 = 24 视口,
 * 所以这里按 `width / 24` 等比放大,两版的视觉尺寸一致。
 */
fun DrawScope.drawIcon(icon: Ng2nIcon, tint: Color) {
  val factor = size.width / ICON_VIEWPORT
  scale(factor, factor, pivot = Offset.Zero) { drawPath(pathOf(icon), tint) }
}
