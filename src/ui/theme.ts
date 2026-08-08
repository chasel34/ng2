import { StyleSheet, useColorScheme } from 'react-native';
import type { ImageStyle, TextStyle, ViewStyle } from 'react-native';

import { useSettings } from '@/store/settings';
import { resolveColorScheme, useThemeMode } from '@/store/theme';

import { paletteOf, type PaletteName, type Theme } from './tokens';

export type { Theme } from './tokens';

/** 当前生效的主题:深浅由夜间模式档位定,浅色下的配色再按「主题风格」分墨绿/纯白。 */
export function useTheme(): Theme {
  const mode = useThemeMode((state) => state.mode);
  const style = useSettings((state) => state.settings.themeStyle);
  const systemScheme = useColorScheme();
  return paletteOf(resolveColorScheme(mode, systemScheme), style);
}

type NamedStyles = Record<string, ViewStyle | TextStyle | ImageStyle>;

/**
 * 声明一份跟着主题走的样式表,返回读它的 hook:
 *
 *     const useStyles = createThemedStyles((t) => ({ page: { backgroundColor: t.colors.bg } }));
 *
 * 每套配色只 StyleSheet.create 一次并缓存,换配色时自动换表。
 */
export function createThemedStyles<T extends NamedStyles>(
  factory: (theme: Theme) => T,
): () => T {
  const cache = new Map<PaletteName, T>();

  return function useThemedStyles(): T {
    const theme = useTheme();
    const cached = cache.get(theme.palette);
    if (cached) {
      return cached;
    }
    const created = StyleSheet.create(factory(theme));
    cache.set(theme.palette, created);
    return created;
  };
}
