/**
 * 动效 token —— 全局唯一的时长/缓动/位移来源。
 *
 * 设计稿(design/project/NGA客户端.dc.html 第 38–40 行)只声明了三条 keyframe,
 * 整份原型的入场动画都是它们的组合:
 *
 *     @keyframes omup  { translateY(14px) + opacity 0→1 }   上滑入场
 *     @keyframes omfade{ opacity 0→1 }                       淡入
 *     @keyframes ompop { scale(.94)  + opacity 0→1 }         弹出
 *
 * 每处用法的时长在 markup 里(`animation:omup .22s ease`),缓动一律是 CSS 的
 * `ease` —— 也就是 `cubic-bezier(.25,.1,.25,1)`,不是 RN 的 `Easing.out(quad)`。
 * 这两条曲线尾段差得挺明显(quad 收得更急),所以这里把 `ease` 显式复刻出来,
 * 页面一律从这儿取值,不再各写各的 `Easing.out(...)` 与魔法毫秒数。
 */

import { Easing } from 'react-native';
import { Easing as ReanimatedEasing } from 'react-native-reanimated';

/** CSS `ease`。设计稿里所有 `animation:… ease` 与未标缓动的 `transition` 都是它。 */
export const easeStandard = Easing.bezier(0.25, 0.1, 0.25, 1);

/**
 * 同一条 CSS `ease`,给 Reanimated 用(两份并存的原因见 `easeDecelerateWorklet`)。
 *
 * 抽屉从 RN `Animated.timing` 迁到 Reanimated 就是为了它:RN 的 timing 在 JS 侧
 * 把缓动曲线预采样成 **60fps 关键帧数组**,原生驱动按 16.67ms 桶取值不插值,
 * 120Hz 屏上每两个 vsync 才前进一步(实测面板位移与遮罩透明度成对重复)。
 * Reanimated 的 `withTiming` 每个 vsync 现算曲线,才是真 120Hz。
 */
export const easeStandardWorklet = ReanimatedEasing.bezier(0.25, 0.1, 0.25, 1);

/**
 * 列表/详情横滑翻页松手后的回弹。设计稿在 support.js 里给的是
 * `transition:transform .22s cubic-bezier(.2,.8,.3,1)` —— 一条起步快、尾段极缓的曲线,
 * 跟手势甩出去的手感配套,与入场用的 `ease` 不是同一条。
 */
export const easeDecelerate = Easing.bezier(0.2, 0.8, 0.3, 1);

/**
 * 同一条 `cubic-bezier(.2,.8,.3,1)`,给 Reanimated 的 `withTiming` 用。
 *
 * **为什么要两份**:上面那条是 `react-native` 的 `Animated.Easing`,是一个跑在 JS 线程上的
 * 普通闭包;Reanimated 的动画跑在 UI 线程,只吃自己那套 `EasingFunctionFactory`
 * (能被序列化搬过去)。两者互不通用,拿错了会直接报 worklet 相关的运行时错。
 * 所以这不是复制粘贴遗留,是两条运行时各要一份。
 *
 * **改控制点时两条一起改**——它们说的是设计稿里同一条曲线,分开漂了就没人发现。
 * 详情页横滑翻页(`app/topic/[tid].tsx` 的 `useSwipePaging`)用的是这一条。
 */
export const easeDecelerateWorklet = ReanimatedEasing.bezier(0.2, 0.8, 0.3, 1);

/**
 * 三种入场动效的时长。同一种动效在设计稿里按元素轻重分了几档:
 * 越大的面(抽屉、对话框)越慢,越小的浮层(菜单、FAB 菜单)越快。
 */
export const duration = {
  /** 弹出菜单(设计稿 942 行 `ompop .16s`) */
  menu: 160,
  /** FAB 展开的动作列(256 行 `omup .18s`)、对话框遮罩(952 行 `omfade .18s`)、开关(666 行 `.18s`) */
  quick: 180,
  /** FAB 图标旋转(261 行 `transform .2s`)、对话框面板(953 行 `ompop .2s`)、抽屉遮罩(917 行 `omfade .2s`) */
  base: 200,
  /** 抽屉面板(918 行 `omup .22s`)、snackbar(981 行 `omup .22s`)、横滑回弹(support.js `.22s`) */
  panel: 220,
  /** 详情页「上次读到」提示条(173 行 `omup .28s`)——全稿最慢的一档 */
  notice: 280,
} as const;

/** omup 的起始位移:设计稿 keyframe 写死 14px。 */
export const RISE_OFFSET = 14;

/** ompop 的起始缩放:设计稿 keyframe 写死 .94。 */
export const POP_SCALE = 0.94;

/**
 * 屏幕转场。设计稿的原型是单页切换、没有专门的转场声明,但整份稿子的入场语言就是
 * 「上滑 + 淡入」;expo-router(react-native-screens)能直接表达的最近一档是
 * `slide_from_right`——横推是 Android 的系统语言,保留它;需要「浮上来」的那种
 * (大图查看器、网页兜底)在各自的 Stack.Screen 上单独声明 fade。
 */
export const screenTransition = {
  /** 常规二级页 */
  push: 'slide_from_right',
  /** 盖在当前页上的全屏浮层(大图查看器) */
  overlay: 'fade',
} as const;
