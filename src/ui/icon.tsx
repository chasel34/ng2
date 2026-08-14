import { useFonts } from 'expo-font';
import { memo } from 'react';
import { Text, type StyleProp, type TextStyle } from 'react-native';

import { ICON_GLYPHS, type IconName } from './icons.generated';

export type { IconName };

/**
 * 图标字体族名。字体文件是 Material Icons Outlined(Apache-2.0),
 * 由 scripts/fetch-icon-font.mjs 下载,码点表在 icons.generated.ts。
 *
 * 设计稿用的是它的下一代 Material Symbols Outlined——图标名与轮廓一致,
 * 但可变字体 4 MB 起且 RN 只吃 ttf/otf,所以取静态版(331 KB)。
 */
export const ICON_FONT_FAMILY = 'MaterialIconsOutlined';

/**
 * 加载图标字体。在根布局调一次;没加载完之前图标会渲染成豆腐块,
 * 所以根布局要等它 ready 再放行首屏。
 */
export function useIconFont(): boolean {
  const [loaded] = useFonts({
    [ICON_FONT_FAMILY]: require('../../assets/fonts/MaterialIconsOutlined-Regular.otf'),
  });
  return loaded;
}

export interface IconProps {
  name: IconName;
  /** 字号即图标边长,取设计稿标的 px 值 */
  size: number;
  color: string;
  style?: StyleProp<TextStyle>;
}

/**
 * 字号 × 颜色 → 基础样式的缓存。
 *
 * `Icon` 渲染的是真 `<Text>`(New Arch 下是一个真的 Android View),而一张楼层卡片上
 * 就有五个(发帖设备、赞、踩、回复、更多),加上贴条/附件/引用链更多。样式每次渲染现建
 * 的话,卡片每重渲染一次这些图标就跟着走一遍完整的元素创建 + props diff。
 *
 * 档位是离散的(设计稿标死的 px 值 × 主题色板),所以键的基数天然有限,不用管失效。
 */
const baseStyles = new Map<string, TextStyle>();

function baseStyleOf(size: number, color: string): TextStyle {
  const key = `${size} ${color}`;
  let style = baseStyles.get(key);
  if (style === undefined) {
    style = {
      fontFamily: ICON_FONT_FAMILY,
      fontSize: size,
      color,
      // Android 默认给字体留上下留白,图标按钮里会歪
      includeFontPadding: false,
      textAlign: 'center',
    };
    baseStyles.set(key, style);
  }
  return style;
}

/**
 * 按码点渲染图标(不用连字:部分 Android ROM 的连字支持不稳)。
 *
 * `allowFontScaling={false}` —— 图标跟着系统字号放大会撑破固定尺寸的按钮;
 * 正文字号缩放由 22 票的字号设置单独处理。
 *
 * memo:调用方绝大多数是「name/size/color 三个字面量」,楼层卡片重渲染时
 * 这些图标本可以整个跳过(2026-08 性能走查)。传了 `style` 的调用方要自己保证
 * 那份样式引用稳定,否则和没包一样——仓库里传的都是 StyleSheet 常量。
 */
export const Icon = memo(function Icon({ name, size, color, style }: IconProps) {
  const base = baseStyleOf(size, color);
  return (
    <Text
      allowFontScaling={false}
      selectable={false}
      style={style === undefined ? base : [base, style]}
    >
      {ICON_GLYPHS[name]}
    </Text>
  );
});
