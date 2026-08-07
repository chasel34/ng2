import { StyleSheet, useColorScheme } from 'react-native';
import type { ImageStyle, TextStyle, ViewStyle } from 'react-native';

import { resolveColorScheme, useThemeMode } from '@/store/theme';

import { themes, type ColorScheme, type Theme } from './tokens';

export type { Theme } from './tokens';

/** 当前生效的主题:跟随系统,或被设置页手动覆盖。 */
export function useTheme(): Theme {
  const mode = useThemeMode((state) => state.mode);
  const systemScheme = useColorScheme();
  return themes[resolveColorScheme(mode, systemScheme)];
}

type NamedStyles = Record<string, ViewStyle | TextStyle | ImageStyle>;

/**
 * 声明一份跟着主题走的样式表,返回读它的 hook:
 *
 *     const useStyles = createThemedStyles((t) => ({ page: { backgroundColor: t.colors.bg } }));
 *
 * 每套配色只 StyleSheet.create 一次并缓存,切换深浅色时自动换表。
 */
export function createThemedStyles<T extends NamedStyles>(
  factory: (theme: Theme) => T,
): () => T {
  const cache = new Map<ColorScheme, T>();

  return function useThemedStyles(): T {
    const theme = useTheme();
    const cached = cache.get(theme.scheme);
    if (cached) {
      return cached;
    }
    const created = StyleSheet.create(factory(theme));
    cache.set(theme.scheme, created);
    return created;
  };
}
