import { useFonts } from 'expo-font';
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
 * 按码点渲染图标(不用连字:部分 Android ROM 的连字支持不稳)。
 *
 * `allowFontScaling={false}` —— 图标跟着系统字号放大会撑破固定尺寸的按钮;
 * 正文字号缩放由 22 票的字号设置单独处理。
 */
export function Icon({ name, size, color, style }: IconProps) {
  return (
    <Text
      allowFontScaling={false}
      selectable={false}
      style={[
        {
          fontFamily: ICON_FONT_FAMILY,
          fontSize: size,
          color,
          // Android 默认给字体留上下留白,图标按钮里会歪
          includeFontPadding: false,
          textAlign: 'center',
        },
        style,
      ]}
    >
      {ICON_GLYPHS[name]}
    </Text>
  );
}
