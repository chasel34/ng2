package com.chasel.ng2n.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 动效 token —— 全局唯一的时长/缓动/位移来源。**直译 RN 侧 `src/ui/motion.ts`**,
 * 数值一个没改(那份文件里每一档都注了设计稿行号)。
 *
 * RN 侧那段「必须用 Reanimated、不能用 RN 自带 Animated」的长注释在原生这边不适用
 * (Compose 的动画本来就在每个 vsync 上现算),但**曲线与时长必须一模一样**:
 * 票 19 场景 6 要拿抽屉与 anzong / RN 版逐帧对拍,差一条曲线就对不上。
 */
object Motion {

  /** CSS `ease`。设计稿里所有 `animation:… ease` 与未标缓动的 `transition` 都是它。 */
  val easeStandard: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

  /**
   * 横滑翻页松手后的回弹曲线(设计稿 support.js
   * `transition:transform .22s cubic-bezier(.2,.8,.3,1)`):起步快、尾段极缓。
   */
  val easeDecelerate: Easing = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)

  /** 弹出菜单(设计稿 942 行 `ompop .16s`) */
  const val DURATION_MENU = 160

  /** FAB 动作列 / 对话框遮罩 / 开关(`.18s`) */
  const val DURATION_QUICK = 180

  /** 对话框面板 / 抽屉遮罩 / FAB 图标旋转(`.2s`);抽屉**关**也用这一档 */
  const val DURATION_BASE = 200

  /** 抽屉面板 / snackbar / 横滑回弹(`.22s`);抽屉**开**用这一档 */
  const val DURATION_PANEL = 220

  /** 详情页「上次读到」提示条(`.28s`)——全稿最慢的一档 */
  const val DURATION_NOTICE = 280

  /** omup 的起始位移:设计稿 keyframe 写死 14px。 */
  const val RISE_OFFSET = 14f

  /** ompop 的起始缩放:设计稿 keyframe 写死 .94。 */
  const val POP_SCALE = 0.94f
}
